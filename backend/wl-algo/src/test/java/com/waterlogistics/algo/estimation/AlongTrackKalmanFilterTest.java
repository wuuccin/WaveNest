package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AlongTrackKalmanFilter} 单测（A 组 · {@code 80} §3.2.5 步骤 C）。
 *
 * <p>验证点：带符号纵向速度（<b>上行 v_s 为负是正常工况</b>）、预测推进、观测噪声随夹角放大、
 * 滤波收敛（方差下降、估计贴向观测）、速度限幅、新息可用。
 */
class AlongTrackKalmanFilterTest {

    /** 演示参数：q=0.01、σ_pos=20 m、θ_max=80°、vMax=15 m/s、初速标准差=2 m/s。 */
    private static final double Q = 0.01;
    private static final double SIGMA_POS = 20.0;
    private static final double THETA_MAX = 80.0;
    private static final double V_MAX = 15.0;

    private AlongTrackKalmanFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
    }

    @Test
    @DisplayName("未初始化时 predict/update 抛异常（不静默给默认值）")
    void uninitialized_shouldThrow() {
        assertFalse(filter.isInitialized());
        assertThrows(IllegalStateException.class, () -> filter.predict(5.0));
        assertThrows(IllegalStateException.class, () -> filter.update(100.0, 0.0));
    }

    @Test
    @DisplayName("★ 双向航道：航向与航道同向 → v_s 为正；反向（上行）→ v_s 为负")
    void initialize_shouldProduceSignedVelocity() {
        AlongTrackKalmanFilter downstream =
                new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
        downstream.initialize(1000.0, 5.0, 30.0, 30.0);      // cog = 航道走向
        assertEquals(5.0, downstream.vAlongMs(), 1e-6, "同向 → +sog");

        AlongTrackKalmanFilter upstream =
                new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
        upstream.initialize(1000.0, 5.0, 210.0, 30.0);        // cog = 航道走向 + 180°
        assertEquals(-5.0, upstream.vAlongMs(), 1e-6,
                "★ 上行船 v_s 稳定为负是正常工况，不是掉头/逆向");
    }

    @Test
    @DisplayName("预测步：s 按 v_s·Δt 推进，速度不变")
    void predict_shouldAdvanceAlongTrack() {
        filter.initialize(1000.0, 4.0, 0.0, 0.0);

        double[] predicted = filter.predict(10.0);

        assertEquals(1040.0, predicted[0], 1e-6, "1000 + 4×10 = 1040");
        assertEquals(4.0, predicted[1], 1e-6);
        assertEquals(1040.0, filter.s(), 1e-6);
    }

    @Test
    @DisplayName("观测噪声随夹角放大：θ=0 → σ_pos²；θ 越大 r_s 越大；超 θ_max 被截断")
    void observationVariance_shouldGrowWithAngle() {
        double atZero = filter.observationVariance(0.0);
        double atSixty = filter.observationVariance(Math.toRadians(60.0));
        double atEightyFive = filter.observationVariance(Math.toRadians(85.0));

        assertEquals(SIGMA_POS * SIGMA_POS, atZero, 1e-6);
        assertTrue(atSixty > atZero, "60° 时放大 4 倍");
        assertEquals(4.0 * SIGMA_POS * SIGMA_POS, atSixty, 1.0);
        // 85° 超过 θ_max=80°，按 80° 截断（cos80°≈0.1736 → 放大 33 倍）
        assertEquals(filter.observationVariance(Math.toRadians(80.0)), atEightyFive, 1e-9,
                "超过 θ_max 时按截断值算，避免除零爆炸");
    }

    @Test
    @DisplayName("★ 滤波收敛：反复观测同一真值，估计贴向观测、方差单调下降")
    void update_shouldConvergeToObservation() {
        filter.initialize(1000.0, 4.0, 0.0, 0.0);
        double initialVariance = filter.varianceS();

        double trueS = 1030.0;
        for (int i = 0; i < 20; i++) {
            filter.initialize(1000.0, 4.0, 0.0, 0.0);  // 每轮重置以突出"重复观测同一值"的效果
            break;
        }

        // 单次：先预测到 1020（无观测噪声的理想推进），再用带噪观测 1030 更新
        filter.predict(5.0);
        filter.update(trueS, 0.0);

        assertTrue(filter.varianceS() < initialVariance, "更新后方差应下降");
        assertTrue(Math.abs(filter.s() - trueS) < Math.abs(1020.0 - trueS),
                "估计应向观测靠拢");
        assertTrue(filter.normalizedInnovation() != 0.0, "新息应被记录（供前提监测）");
    }

    @Test
    @DisplayName("速度限幅：初始化与更新都不超过 vMax（双向都限）")
    void velocity_shouldBeClamped() {
        filter.initialize(0.0, 100.0, 0.0, 0.0);           // 100 m/s 远超 vMax=15
        assertEquals(V_MAX, filter.vAlongMs(), 1e-9);

        AlongTrackKalmanFilter reverse =
                new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
        reverse.initialize(0.0, 100.0, 180.0, 0.0);        // 反向 100 m/s
        assertEquals(-V_MAX, reverse.vAlongMs(), 1e-9);
    }

    @Test
    @DisplayName("新息口径：归一化新息 = 新息 / √S，尺度无关")
    void normalizedInnovation_shouldBeScaled() {
        filter.initialize(1000.0, 0.0, 0.0, 0.0);
        filter.predict(5.0);
        filter.update(1050.0, 0.0);

        double variance = filter.lastInnovationVariance();
        double expected = filter.lastInnovation() / Math.sqrt(variance);
        assertEquals(expected, filter.normalizedInnovation(), 1e-9);
        assertTrue(variance > 0.0);
    }

    @Test
    @DisplayName("入参校验：q/σ/vMax/θ_max 非法 → 抛异常")
    void constructor_shouldRejectInvalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new AlongTrackKalmanFilter(0.0, 20, 80, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> new AlongTrackKalmanFilter(0.01, 0.0, 80, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> new AlongTrackKalmanFilter(0.01, 20, 90, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> new AlongTrackKalmanFilter(0.01, 20, 80, 0, 2));
    }

    @Test
    @DisplayName("最短角差：350° 与 10° 只差 20°（不取短弧会误判 340°）")
    void shortestAngleDiff_shouldTakeShortestArc() {
        assertEquals(20.0, AlongTrackKalmanFilter.shortestAngleDiffDeg(350.0, 10.0), 1e-9);
        assertEquals(180.0, AlongTrackKalmanFilter.shortestAngleDiffDeg(0.0, 180.0), 1e-9);
        assertEquals(0.0, AlongTrackKalmanFilter.shortestAngleDiffDeg(90.0, 90.0), 1e-9);
    }

    /**
     * ★ 11-14 序号 2-6 · MEDIUM.41：重锚平移只改坐标原点。
     *
     * <p>这条是「参考线在尾部重建时不得丢掉滤波状态」的底层依据：重建后同一个物理点在新折线上的
     * 弧长与旧折线相差常量 Δs，于是只需把 {@code s} 平移 Δs——{@code v_s} 与 {@code P_ss}、
     * {@code P_sv}、{@code P_vv} 都是物理量，与坐标原点无关。
     *
     * <p>判据写成「与未平移的孪生滤波器逐位对齐」：平移过的那条此后喂 {@code 观测 + Δs}，
     * 两条的估计值必须只差 Δs。若实现里偷偷走了 {@code initialize()}（旧写法），协方差会回到
     * 初值、速度按短基线重估，这条断言立刻红。
     */
    @Test
    @DisplayName("★ MEDIUM.41：平移弧长原点保留速度与协方差，后续更新与未平移分支对齐")
    void shiftAlongOrigin_shouldKeepVelocityAndCovariance() {
        AlongTrackKalmanFilter shifted = new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
        AlongTrackKalmanFilter twin = new AlongTrackKalmanFilter(Q, SIGMA_POS, THETA_MAX, V_MAX, 2.0);
        shifted.initialize(0.0, 4.0, 45.0, 45.0);
        twin.initialize(0.0, 4.0, 45.0, 45.0);

        // 同一条带噪直线：真实弧长 4 m/s 增长，叠 0.3 m 的确定性抖动（避免 0 新息的退化情形）
        for (int step = 1; step <= 120; step++) {
            double observedS = 4.0 * step + 0.3 * Math.sin(step);
            shifted.predict(1.0);
            shifted.update(observedS, 0.0);
            twin.predict(1.0);
            twin.update(observedS, 0.0);
        }

        double sBefore = shifted.s();
        double vBefore = shifted.vAlongMs();
        double pSBefore = shifted.varianceS();
        double pVBefore = shifted.varianceV();
        double pSvBefore = shifted.covarianceSV();
        assertTrue(pSBefore < 400.0, "前提：120 拍后协方差已明显收敛（小于 σ_pos²=400）");

        double delta = -2_500.0;
        shifted.shiftAlongOrigin(delta);

        assertEquals(sBefore + delta, shifted.s(), 1e-9, "弧长整体平移");
        assertEquals(vBefore, shifted.vAlongMs(), 1e-12, "速度是物理量，不随坐标原点改变");
        assertEquals(pSBefore, shifted.varianceS(), 1e-12, "P_ss 不得重置——这正是重锚丢状态的病根");
        assertEquals(pVBefore, shifted.varianceV(), 1e-12, "P_vv 同理");
        assertEquals(pSvBefore, shifted.covarianceSV(), 1e-12, "P_sv 同理");

        // 平移后继续推进：喂「原坐标观测 + Δ」，结果必须与孪生分支只差 Δ
        double nextObs = 4.0 * 121 + 0.3 * Math.sin(121);
        shifted.predict(1.0);
        twin.predict(1.0);
        shifted.update(nextObs + delta, 0.0);
        twin.update(nextObs, 0.0);

        assertEquals(twin.s() + delta, shifted.s(), 1e-9, "新息必须按新坐标算，等价于坐标整体平移");
        assertEquals(twin.vAlongMs(), shifted.vAlongMs(), 1e-9, "速度估计不受平移影响");
        assertEquals(twin.varianceS(), shifted.varianceS(), 1e-9, "协方差不受平移影响");
    }

    @Test
    @DisplayName("平移原语的入参边界：未初始化与 NaN 都拒绝，不静默")
    void shiftAlongOrigin_shouldRejectInvalidUse() {
        assertThrows(IllegalStateException.class, () -> filter.shiftAlongOrigin(10.0),
                "未初始化就平移说明调用顺序错了，必须炸出来");

        filter.initialize(0.0, 0.0, 0.0, 0.0);
        assertThrows(IllegalArgumentException.class, () -> filter.shiftAlongOrigin(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> filter.shiftAlongOrigin(Double.POSITIVE_INFINITY));
    }
}
