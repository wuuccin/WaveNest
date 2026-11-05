package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.dispatch.prediction.PrecisePredictionEngine.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 沿航道速度偏置的读取点（11-04 序号 2 · 77 §8 G1 裁定选①：补读取点、不删键）。
 *
 * <p>断链事实：校准器产出 {@code ALONG_SPEED_BIAS} 建议 → {@link PredictionCalibrationService}
 * 映射到键 {@code predAlongSpeedBiasMs} → {@code 007} 有种子，<b>写回链路是通的</b>；
 * 但全仓无读取方，预测速度从不吃这个偏置。本类验的就是「补上读取点之后行为对不对」。
 *
 * <h3>符号为什么必须单独验（写反会让预测越校越偏，且不报错）</h3>
 * <p>偏置 {@code bias = μ_along / τ̄}，{@code μ_along = s_est − s_pred}。{@code s} 是沿参考线的
 * <b>绝对弧长</b>：上行船 {@code v_s < 0}、下行船 {@code v_s > 0}，但两者在同一个坐标系里。
 * 因此 {@code bias > 0} 的语义恒为「往 {@code s} 增大的一侧修」，与船往哪头开无关——
 * 若按直觉乘 {@code sign(v_s)}，上行船的偏置会被推反。
 *
 * <h3>为什么夹具是正北直线</h3>
 * <p>{@code s} 等价于北向位移，断言可以把纬度差乘 {@code METERS_PER_DEG_LAT} 直接换算成米，
 * 不必再写一套投影反算——夹具的几何复杂度降到零，失败时只需怀疑偏置本身。
 */
class PrecisePredictionEngineAlongBiasTest {

    private static final String MMSI = "413000002";

    /** 与生产 {@code sys_param} 同值：α_along=0.10、α_cross=0.03、时域 1800 s、步长 60 s。 */
    private static final PredictionSettings SETTINGS =
            new PredictionSettings(20.0, 0.10, 15.0, 0.03, 1800L, 60L, 900L);

    /** 正北向直线（28.0 → 28.5，约 55.6 km）：{@code s} = 北向位移。 */
    private static final WaterwayGeometry CENTERLINE = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(116.0, 28.0),
            new WaterwayGeometry.Vertex(116.0, 28.5)));

    private static final double ORIGIN_LON = 116.0;
    private static final double ORIGIN_LAT = 28.1;
    private static final long T0 = 1_760_000_000_000L;

    /** 8 节 ≈ 4.115 m/s：每秒走这么远（与观测里的 sog=8.0 自洽）。 */
    private static final double STEP_M = 4.115;

    private static final double MPD_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;

    private static PrecisePredictionEngine engine(double biasMs) {
        return engine(() -> biasMs);
    }

    private static PrecisePredictionEngine engine(DoubleSupplier bias) {
        return new PrecisePredictionEngine(SETTINGS, PrecisePredictionEngine.Params.defaults(),
                CENTERLINE, null, bias);
    }

    @Test
    @DisplayName("偏置缺省为 0：与接入前的构造器产出逐点相同（接入不改变现状）")
    void zeroBias_shouldBehaveExactlyLikeBefore() {
        PrecisePredictionEngine legacy = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE);
        PrecisePredictionEngine explicitZero = engine(0.0);

        AisMessage tailA = feed(legacy, 60);
        AisMessage tailB = feed(explicitZero, 60);

        List<EstimatedPoint> a = predictAt(legacy, tailA).points();
        List<EstimatedPoint> b = predictAt(explicitZero, tailB).points();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).lat(), b.get(i).lat(), 0.0,
                    "τ=" + i * SETTINGS.stepSec() + " s 处偏置为 0 却改了位置");
            assertEquals(a.get(i).lon(), b.get(i).lon(), 0.0);
        }
    }

    @Test
    @DisplayName("非 0 偏置：预测点沿航道整体平移 bias·τ（下行船）")
    void positiveBias_shouldShiftPredictionByBiasTimesTau() {
        double bias = 0.5;
        PrecisePredictionEngine without = engine(0.0);
        PrecisePredictionEngine with = engine(bias);

        AisMessage tailA = feed(without, 60);
        AisMessage tailB = feed(with, 60);

        List<EstimatedPoint> base = predictAt(without, tailA).points();
        List<EstimatedPoint> shifted = predictAt(with, tailB).points();

        for (int i = 1; i < base.size(); i++) {
            double tauSec = i * SETTINGS.stepSec();
            double deltaM = (shifted.get(i).lat() - base.get(i).lat()) * MPD_LAT;
            assertEquals(bias * tauSec, deltaM, 1e-3,
                    "τ=" + tauSec + " s 的沿程平移量不符：偏置没有进推进速度");
        }
    }

    @Test
    @DisplayName("上行船（v<0）：正偏置仍往 s 增大方向修——不得乘 sign(v) 把符号推反")
    void positiveBias_upstreamShip_shouldStillShiftTowardIncreasingS() {
        double bias = 0.5;
        PrecisePredictionEngine without = engine(0.0);
        PrecisePredictionEngine with = engine(bias);

        // 沿正南直航（cog=180）：v_s < 0，s 随时间是减小的
        AisMessage tailA = feedSouthbound(without, 60);
        AisMessage tailB = feedSouthbound(with, 60);

        List<EstimatedPoint> base = predictAt(without, tailA).points();
        List<EstimatedPoint> shifted = predictAt(with, tailB).points();

        // 前提校验：这条船确实在往 s 减小方向走，否则本用例验不到符号
        assertTrue(base.get(6).lat() < base.get(0).lat(), "夹具应有 v_s < 0（s 递减）");

        int index = 6;                       // τ = 360 s
        double tauSec = index * SETTINGS.stepSec();
        double deltaM = (shifted.get(index).lat() - base.get(index).lat()) * MPD_LAT;
        assertEquals(bias * tauSec, deltaM, 1e-3,
                "上行船的正偏置应使 s 更大（少往南走），实测平移 " + deltaM + " m");
    }

    @Test
    @DisplayName("偏置在每次预测时读取：运行期回写即时生效，不需要重启")
    void bias_shouldBeReadAtEachPrediction() {
        double[] holder = {0.0};
        PrecisePredictionEngine engine = engine(() -> holder[0]);
        AisMessage tail = feed(engine, 60);

        Outcome before = predictAt(engine, tail);
        holder[0] = 0.4;                                     // 校准器回写（同一实例，不重建）
        Outcome after = predictAt(engine, tail);

        double tauSec = SETTINGS.horizonSec();
        double deltaM = (after.points().get(after.points().size() - 1).lat()
                - before.points().get(before.points().size() - 1).lat()) * MPD_LAT;
        assertEquals(0.4 * tauSec, deltaM, 1e-3,
                "偏置只在构造时读一次的话回写不会生效——正是 G1 要修的那条断链");
    }

    @Test
    @DisplayName("护栏：静止船不加偏置（不把停泊船预测成漂移）")
    void stoppedShip_shouldIgnoreBias() {
        PrecisePredictionEngine engine = engine(0.5);
        assertEquals(0.0, engine.alongSpeedForPrediction(0.0), 0.0);
        assertEquals(0.05, engine.alongSpeedForPrediction(0.05), 0.0,
                "0.05 m/s 低于生效门限，应原样返回");
        assertEquals(-0.05, engine.alongSpeedForPrediction(-0.05), 0.0);
    }

    @Test
    @DisplayName("护栏：偏置不得把船推向反方向（撞零就停在 0）")
    void bias_mustNotReverseDirection() {
        PrecisePredictionEngine down = engine(-0.5);
        assertEquals(0.0, down.alongSpeedForPrediction(0.2), 0.0,
                "0.2 − 0.5 会跨过零点，应停在 0 而不是倒退");
        assertEquals(3.5, down.alongSpeedForPrediction(4.0), 1e-12);

        PrecisePredictionEngine up = engine(0.5);
        assertEquals(0.0, up.alongSpeedForPrediction(-0.2), 0.0);
        assertEquals(-3.5, up.alongSpeedForPrediction(-4.0), 1e-12);
    }

    @Test
    @DisplayName("非法取值（NaN／Infinity）不生效：不合法偏置不能污染预测")
    void nonFiniteBias_shouldBeIgnored() {
        assertEquals(4.0, engine(Double.NaN).alongSpeedForPrediction(4.0), 0.0);
        assertEquals(4.0, engine(Double.POSITIVE_INFINITY).alongSpeedForPrediction(4.0), 0.0);
    }

    // ───────────── 夹具 ─────────────

    /**
     * 用<b>最后那条观测原样再喂一次</b>取一版预测。
     *
     * <p>为什么不新造一条更晚的观测：那会推进滤波器状态，两次取预测的状态就不同，
     * 差值里混进状态变化，验不到偏置。重复时刻的观测按引擎注释「不推进状态、仍出一版预测」处理。
     */
    private static Outcome predictAt(PrecisePredictionEngine engine, AisMessage lastObservation) {
        Optional<Outcome> outcome = engine.predict(lastObservation);
        assertTrue(outcome.isPresent(), "冷启动早已完成，应能给出精算预测");
        return outcome.get();
    }

    /** 沿正北直航 {@code seconds} 秒，每秒一条观测；返回最后那条观测。 */
    private static AisMessage feed(PrecisePredictionEngine engine, int seconds) {
        AisMessage last = null;
        for (int second = 0; second <= seconds; second++) {
            double[] point = offset(ORIGIN_LON, ORIGIN_LAT, STEP_M * second, 0.0);
            last = observation(point, 0.0, T0 + second * 1_000L);
            engine.recordVersion(MMSI, second + 1L);
            engine.predict(last);
        }
        return last;
    }

    /** 沿正南直航（cog=180），每秒一条观测；返回最后那条观测。 */
    private static AisMessage feedSouthbound(PrecisePredictionEngine engine, int seconds) {
        AisMessage last = null;
        for (int second = 0; second <= seconds; second++) {
            double[] point = offset(ORIGIN_LON, ORIGIN_LAT + 0.02, -STEP_M * second, 0.0);
            last = observation(point, 180.0, T0 + second * 1_000L);
            engine.recordVersion(MMSI, second + 1L);
            engine.predict(last);
        }
        return last;
    }

    private static AisMessage observation(double[] point, double cogDeg, long ts) {
        return new AisMessage(MMSI, "测试船", point[0], point[1], 8.0, cogDeg, ts);
    }

    private static double[] offset(double lon, double lat, double alongM, double crossM) {
        double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(lat));
        return new double[] {lon + crossM / metersPerDegLon, lat + alongM / MPD_LAT};
    }
}
