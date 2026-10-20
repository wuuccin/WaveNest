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
}
