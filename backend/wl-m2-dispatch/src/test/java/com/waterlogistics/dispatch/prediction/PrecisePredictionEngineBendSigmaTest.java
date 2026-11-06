package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 横向不确定度随中心线曲率变化的端到端验证（11-06 序号 2 · 卢肄文）。
 *
 * <p>判据「接入后精算路径的横向不确定度随中心线曲率变化，不再是固定系数」在这条链路上
 * 有两个可测形式：
 * <ol>
 *   <li><b>同一位置、同一观测</b>，只把中心线从直线换成带弯道，{@code σ_cross} 必须变大；</li>
 *   <li>直线中心线上的 {@code σ_cross} 与改动前逐点一致（观测口径 {@code σ_e0} 原样生效），
 *       保证「回归既有精算用例不失败」。</li>
 * </ol>
 *
 * <p>为什么比较 {@code τ = 300 s} 而不是时域末点：{@code α_cross·τ} 会把末点顶到
 * {@code laneHalfWidthM = 50 m} 的截断上界，两条曲线都被截断就成了 50 == 50，测不出差别。
 * 取 300 s 时两侧都还没截断，差的正是几何项。
 */
class PrecisePredictionEngineBendSigmaTest {

    private static final PredictionSettings SETTINGS =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    /** 比较用的预测时刻，s（两侧都未触到半宽截断）。 */
    private static final int TAU_COMPARE_SEC = 300;

    private static final long T0_MS = 1_000_000L;

    private static final double M_PER_DEG_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;

    /** 起点，船在这一点上观测。 */
    private static final double LON0 = 115.86;
    private static final double LAT0 = 28.66;

    /** 一条正北直线中心线：船位落在 3000 m 处（与折线中心线同里程）。 */
    private static WaterwayGeometry straightCenterline() {
        return new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(LON0, LAT0 - 3000.0 / M_PER_DEG_LAT),
                new WaterwayGeometry.Vertex(LON0, LAT0 + 3000.0 / M_PER_DEG_LAT)));
    }

    /**
     * 同一走向但折点处偏 20° 的中心线，折点就在船位上。
     *
     * <p>折点与船位同纬度、同经度，故两条中心线上船位的 {@code s} 都是 3000 m——
     * σ 的差异只可能来自曲率，不会混入里程差。
     */
    private static WaterwayGeometry bentCenterline() {
        double armM = 3000.0;
        double rad = Math.toRadians(20.0);
        double metersPerDegLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(LAT0));
        WaterwayGeometry.Vertex bend = new WaterwayGeometry.Vertex(LON0, LAT0);
        WaterwayGeometry.Vertex after = new WaterwayGeometry.Vertex(
                LON0 + armM * Math.sin(rad) / metersPerDegLon,
                LAT0 + armM * Math.cos(rad) / M_PER_DEG_LAT);
        return new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(LON0, LAT0 - armM / M_PER_DEG_LAT), bend, after));
    }

    /** 船以 7.78 kn（≈4 m/s）正北航行的一帧观测。 */
    private static AisMessage observation(long tsMs) {
        return new AisMessage("413000001", "测试船", LON0, LAT0, 7.78, 0.0, tsMs);
    }

    /** 同一船的下一帧观测（向北挪 {@code northM} 米）。 */
    private static AisMessage observation(long tsMs, double northM) {
        return new AisMessage("413000001", "测试船", LON0, LAT0 + northM / M_PER_DEG_LAT,
                7.78, 0.0, tsMs);
    }

    /** 取 {@code τ} 对应的那个点（点列按 {@code stepSec = 60} 等间隔，索引即 τ/60）。 */
    private static double sigmaCrossAt(PrecisePredictionEngine.Outcome outcome, int tauSec) {
        int index = tauSec / (int) SETTINGS.stepSec();
        List<EstimatedPoint> points = outcome.points();
        assertTrue(index < points.size(), "点列里没有 τ=" + tauSec + " s 的点");
        return points.get(index).sigmaCrossM();
    }

    /** 单条观测即可出预测（中心线给定时）。 */
    private static PrecisePredictionEngine.Outcome predictOne(WaterwayGeometry centerline) {
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), centerline);
        return engine.predict(observation(T0_MS)).orElseThrow();
    }

    @Test
    @DisplayName("直线中心线：σ_cross 落在观测口径上，几何项为 0 不加料")
    void straightCenterline_sigmaShouldStayAtObservationFloor() {
        PrecisePredictionEngine.Outcome outcome = predictOne(straightCenterline());

        // σ_cross(τ) = sqrt(Var_e(τ)) + α_cross·τ；σ_e 未抬升时 Var_e(300) 已收敛到 σ_e0²
        double expected = SETTINGS.sigma0CrossM() + SETTINGS.alphaCrossMs() * TAU_COMPARE_SEC;
        assertEquals(expected, sigmaCrossAt(outcome, TAU_COMPARE_SEC), 0.1,
                "直线段应严格落在观测口径上（σ_e0 + α·τ），不得因接入弯道项而虚增");
    }

    @Test
    @DisplayName("弯道中心线：同一船位的 σ_cross 明显大于直线航段")
    void bentCenterline_sigmaShouldExceedStraight() {
        double straightSigma = sigmaCrossAt(predictOne(straightCenterline()), TAU_COMPARE_SEC);
        double bentSigma = sigmaCrossAt(predictOne(bentCenterline()), TAU_COMPARE_SEC);

        // 20° 弯、窗宽 500 m → κ≈6.98e-4 → 弦高≈21.8 m，σ_e 由 15 抬到 ≈26.5 m，差约 11 m
        assertTrue(bentSigma > straightSigma + 5.0,
                "弯道 σ_cross 应明显大于直线航段：直线=" + straightSigma + " 弯道=" + bentSigma);
    }

    @Test
    @DisplayName("τ=0 的点不带几何项：当前观测不存在参考线偏差")
    void currentPoint_shouldNotCarryBendInflation() {
        assertEquals(sigmaCrossAt(predictOne(straightCenterline()), 0),
                sigmaCrossAt(predictOne(bentCenterline()), 0), 1e-9,
                "τ=0 是当前实测点本身，弦高属于沿线预测的参考线偏差，不该加到当前点上");
    }

    @Test
    @DisplayName("无中心线的退化口径：σ_cross 不被逐船噪声折线带偏")
    void withoutCenterline_sigmaShouldNotBeInflated() {
        // 无中心线时引擎用实测窗口自建参考线：两条观测相隔 60 s、北移 6 m
        // （≥ MIN_TRACK_GAP_M=5 m 才收为第二个顶点；跨度仍远小于 60 m，走自报航向的直线口径）
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults());
        PrecisePredictionEngine.Outcome first = engine.predict(observation(T0_MS)).orElse(null);
        // 首帧建不出参考线（不足两点），属预期；第二条观测才初始化
        assertTrue(first == null, "无中心线且只有一条观测时不该出预测");
        PrecisePredictionEngine.Outcome outcome =
                engine.predict(observation(T0_MS + 60_000L, 6.0)).orElseThrow();

        double expected = SETTINGS.sigma0CrossM() + SETTINGS.alphaCrossMs() * TAU_COMPARE_SEC;
        assertEquals(expected, sigmaCrossAt(outcome, TAU_COMPARE_SEC), 0.1,
                "自建参考线没有江道曲率语义，不得据此抬升横向带宽（否则正常航行船会被无端加宽）");
    }
}
