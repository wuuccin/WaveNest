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

    // ───────────────── 台账 v2 十三点（11-07 序号 2 · 判据 19.43 m / 720 s） ─────────────────

    /**
     * 中心线台账 v2 的十三个顶点，逐点取自 {@code deploy/sql/014_centerline_v2.sql}。
     *
     * <p>全长 {@code 5778.9088 m}；唯一实折点在 {@code s = 2009.9314 m}（转角 11.32°）。
     */
    private static final double[][] V2_POINTS = {
        {115.852, 28.646}, {115.853739, 28.650224}, {115.855479, 28.654448},
        {115.857218, 28.658673}, {115.859, 28.663}, {115.861586, 28.666749},
        {115.864236, 28.670593}, {115.866887, 28.674436}, {115.869537, 28.678279},
        {115.872188, 28.682123}, {115.874838, 28.685966}, {115.877489, 28.689809},
        {115.879, 28.692}
    };

    /** 唯一实折点的弧长，m。 */
    private static final double V2_BEND_S = 2009.9314;

    /** 折点之外的直线取样点，m（用于对照 validitySec）。 */
    private static final double V2_STRAIGHT_S = 4500.0;

    private static WaterwayGeometry v2Centerline() {
        List<WaterwayGeometry.Vertex> vertices = new java.util.ArrayList<>();
        for (double[] p : V2_POINTS) {
            vertices.add(new WaterwayGeometry.Vertex(p[0], p[1]));
        }
        return new WaterwayGeometry(vertices);
    }

    /**
     * 船恰好位于中心线 {@code s} 处、航向取该处走向的一帧观测。
     *
     * <p>位置与航向都从几何本身取，避免手写坐标与折线不同源而让 {@code s} 漂移——
     * 本用例要比较的是同一里程上的 σ，里程差会直接污染结论。
     */
    private static AisMessage observationAt(WaterwayGeometry centerline, double s) {
        WaterwayGeometry.Vertex point = centerline.pointAt(s);
        return new AisMessage("413000001", "测试船", point.lon(), point.lat(), 7.78,
                centerline.courseDegAt(s), T0_MS);
    }

    private static PrecisePredictionEngine.Outcome predictAt(WaterwayGeometry centerline, double s) {
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), centerline);
        return engine.predict(observationAt(centerline, s)).orElseThrow();
    }

    @Test
    @DisplayName("台账 v2 十三点：弯道 σ_cross 在 τ=720 s 越过 40 m，直线要到 840 s")
    void v2Bend_shouldCrossCrossTrackToleranceAt720() {
        WaterwayGeometry centerline = v2Centerline();

        // 先钉住输入口径：全长与 014 的 s_m 末行一致，否则下面的 s 取样点不对应真实折点
        assertEquals(5778.9088, centerline.totalLengthM(), 0.001,
                "十三点全长须与台账 s_m 末行一致");

        PrecisePredictionEngine.Outcome atBend = predictAt(centerline, V2_BEND_S);
        PrecisePredictionEngine.Outcome atStraight = predictAt(centerline, V2_STRAIGHT_S);

        assertEquals(19.43, CenterlineBendRef.sigmaE(centerline, V2_BEND_S), 0.01,
                "折点处 σ_e 应为 19.43 m（√(15² + 12.34²)）");
        assertEquals(15.00, CenterlineBendRef.sigmaE(centerline, V2_STRAIGHT_S), 1e-3,
                "直线段 σ_e 停在观测口径 15.00 m");

        // 横向越限时距：弯道 720 s 首次 >40 m，直线到 840 s 才越限
        assertEquals(41.03, sigmaCrossAt(atBend, 720), 0.05,
                "弯道 σ_cross 在 τ=720 s 为 41.03 m，越过 wTolM=40 m");
        assertEquals(36.60, sigmaCrossAt(atStraight, 720), 0.05,
                "同里程的直线段在 τ=720 s 只有 36.60 m，尚未越限");
        assertEquals(40.20, sigmaCrossAt(atStraight, 840), 0.05,
                "直线段在 τ=840 s 才到 40.20 m");

        // 700 s 前后两侧都还没截断，弯道恒大于直线：几何项是唯一差异来源
        assertTrue(sigmaCrossAt(atBend, TAU_COMPARE_SEC)
                        > sigmaCrossAt(atStraight, TAU_COMPARE_SEC) + 4.0,
                "弯道 σ_cross 应比直线大出几何项的量级");
    }

    @Test
    @DisplayName("台账 v2 十三点：validitySec 由纵向先撞线，弯直同为 360 s")
    void v2Validity_isBoundByAlongTrackNotCurvature() {
        WaterwayGeometry centerline = v2Centerline();
        PrecisePredictionEngine.Outcome atBend = predictAt(centerline, V2_BEND_S);
        PrecisePredictionEngine.Outcome atStraight = predictAt(centerline, V2_STRAIGHT_S);

        // validitySec = min{τ : σ_along > lTolM(180 m) 或 σ_cross > wTolM(40 m)}
        // σ_along 在 τ=300 为 166.36 m、τ=360 已越过 180 m，早于任何横向越限，
        // 故两处都停 360 s——曲率影响的是「横向越限时距」，不是 validitySec 本身。
        assertEquals(360L, atBend.validitySec(),
                "弯道 validitySec 仍为 360 s：纵向 σ_along 在 360 s 先越过 lTolM=180 m");
        assertEquals(360L, atStraight.validitySec(),
                "直线段同样 360 s；若此值随曲率变化，说明 validity 的取小口径被改坏了");

        assertEquals(166.36, alongAt(atBend, 300), 0.05,
                "τ=300 的 σ_along=166.36 m 尚未越限，说明 360 s 是纵向撞线时刻");
    }

    /** 取 {@code τ} 处的 σ_along。 */
    private static double alongAt(PrecisePredictionEngine.Outcome outcome, int tauSec) {
        int index = tauSec / (int) SETTINGS.stepSec();
        return outcome.points().get(index).sigmaAlongM();
    }

    /** 只为断言 σ_e 而设的薄包装，避免测试直接依赖 algo 包内部实现细节。 */
    private static final class CenterlineBendRef {
        static double sigmaE(WaterwayGeometry geometry, double s) {
            return com.waterlogistics.algo.estimation.CenterlineBend
                    .sigmaEPredictionM(SETTINGS.sigma0CrossM(), 50.0, geometry, s);
        }
    }
}
