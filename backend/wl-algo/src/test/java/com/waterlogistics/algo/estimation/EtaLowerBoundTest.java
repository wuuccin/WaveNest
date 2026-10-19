package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EtaLowerBound} 单测（A 组 · {@code 80} §3.1.3「etaMin 最短任务时长下界」）。
 *
 * <p>验证点：公式口径正确、<b>对参数敏感</b>（机速↑→下界↓、巢距离↑→下界↑、点数↑→最小距离不增）、
 * 以及"下界赶不上即可安全判时间不可行"这一单向推理。
 */
class EtaLowerBoundTest {

    private static final EtaLowerBound.NestPosition NEST_A =
            new EtaLowerBound.NestPosition("NEST-A", 115.852, 28.646);
    private static final EtaLowerBound.NestPosition NEST_B =
            new EtaLowerBound.NestPosition("NEST-B", 115.879, 28.692);

    /** 造一个位于指定经纬度的预测点。 */
    private static EstimatedPoint point(long tMs, double lon, double lat) {
        return new EstimatedPoint(tMs, lon, lat, 20.0, 15.0);
    }

    @Test
    @DisplayName("船正好在机巢上：最近距离 0，etaMin = 最短交接耗时")
    void shipAtNest_shouldHaveZeroTravelTime() {
        List<EstimatedPoint> points = List.of(point(1000L, NEST_A.lon(), NEST_A.lat()));

        EtaLowerBound.Result result =
                EtaLowerBound.compute(points, List.of(NEST_A), 12.0, 60.0);

        assertEquals(0.0, result.nearestDistanceM(), 1.0);
        assertEquals(60.0, result.etaLowerBoundSec(), 1.0, "无位移时 etaMin 只剩交接耗时");
        assertEquals("NEST-A", result.nearestNestId());
    }

    @Test
    @DisplayName("公式口径：etaMin = t_handover + 2·dist/v（去程＋返程两段）")
    void etaMin_shouldFollowFormula() {
        // 让船距 NEST_A 约 1000 m：纬度偏 0.00898° ≈ 1000 m
        double lat = NEST_A.lat() + 1000.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        List<EstimatedPoint> points = List.of(point(1000L, NEST_A.lon(), lat));

        EtaLowerBound.Result result =
                EtaLowerBound.compute(points, List.of(NEST_A), 10.0, 60.0);

        double expected = 60.0 + 2.0 * result.nearestDistanceM() / 10.0;
        assertEquals(expected, result.etaLowerBoundSec(), 1e-6);
        assertEquals(1000.0, result.nearestDistanceM(), 5.0);
    }

    @Test
    @DisplayName("★ 参数敏感性：机速加倍 → 飞行项减半（下界变小）")
    void fasterDrone_shouldLowerEtaMin() {
        double lat = NEST_A.lat() + 1000.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        List<EstimatedPoint> points = List.of(point(1000L, NEST_A.lon(), lat));

        double slow = EtaLowerBound.compute(points, List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();
        double fast = EtaLowerBound.compute(points, List.of(NEST_A), 20.0, 0.0).etaLowerBoundSec();

        assertEquals(200.0, slow, 2.0, "2×1000/10 = 200 s");
        assertEquals(100.0, fast, 2.0, "2×1000/20 = 100 s");
        assertTrue(fast < slow);
    }

    @Test
    @DisplayName("★ 参数敏感性：船更远 → 下界更大；多给一个更近的巢 → 下界变小")
    void distanceAndNestChoice_shouldDriveEtaMin() {
        EstimatedPoint near = point(1000L, NEST_A.lon(), NEST_A.lat());
        double farLat = NEST_A.lat() + 5000.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        EstimatedPoint far = point(1000L, NEST_A.lon(), farLat);

        double nearEta = EtaLowerBound.compute(List.of(near), List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();
        double farEta = EtaLowerBound.compute(List.of(far), List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();
        assertTrue(farEta > nearEta, "船更远，下界应更大");

        // 船在 B 巢附近，但候选只给 A 巢 → 下界很大；把 B 巢加进候选 → 下界骤降
        EstimatedPoint nearB = point(1000L, NEST_B.lon(), NEST_B.lat());
        double onlyA = EtaLowerBound.compute(List.of(nearB), List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();
        double bothNests = EtaLowerBound.compute(List.of(nearB), List.of(NEST_A, NEST_B), 10.0, 0.0)
                .etaLowerBoundSec();
        assertTrue(bothNests < onlyA, "把更近的巢纳入候选，下界应显著下降");
    }

    @Test
    @DisplayName("★ 参数敏感性：预测点更多 → 最小距离只会更小或不变（下界不增）")
    void morePoints_shouldNotIncreaseEtaMin() {
        double lat = NEST_A.lat() + 3000.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;

        List<EstimatedPoint> sparse = List.of(point(1000L, NEST_A.lon(), lat));
        List<EstimatedPoint> dense = List.of(
                point(1000L, NEST_A.lon(), lat),
                point(61000L, NEST_A.lon(), NEST_A.lat()));   // 第二个点正好在巢上

        double sparseEta = EtaLowerBound.compute(sparse, List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();
        double denseEta = EtaLowerBound.compute(dense, List.of(NEST_A), 10.0, 0.0).etaLowerBoundSec();

        assertTrue(denseEta <= sparseEta, "点列更密，取到的最近距离不可能更远");
        assertEquals(0.0, denseEta, 1.0, "有点落在巢上，飞行项为 0");
    }

    @Test
    @DisplayName("判据：可用时长 < 下界 → 必然赶不上（可安全判 TIME_INFEASIBLE）")
    void isTimeInfeasible_shouldBeConservative() {
        long now = 1_000_000L;

        // 可用 300 s，下界 260 s → 不能说"必然赶不上"
        assertFalse(EtaLowerBound.isTimeInfeasible(now + 300_000L, now, 260.0));
        // 可用 200 s，下界 260 s → 下界都超了，必然赶不上
        assertTrue(EtaLowerBound.isTimeInfeasible(now + 200_000L, now, 260.0));
        // 边界：正好相等不算不可行（保守：只在严格超出时拒单）
        assertFalse(EtaLowerBound.isTimeInfeasible(now + 260_000L, now, 260.0));
    }

    @Test
    @DisplayName("入参校验：空点列 / 空机巢 / 非正速度 / 负交接耗时 → 抛异常")
    void compute_shouldRejectInvalidInput() {
        List<EstimatedPoint> points = List.of(point(1000L, NEST_A.lon(), NEST_A.lat()));

        assertThrows(IllegalArgumentException.class,
                () -> EtaLowerBound.compute(List.of(), List.of(NEST_A), 10.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> EtaLowerBound.compute(points, List.of(), 10.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> EtaLowerBound.compute(points, List.of(NEST_A), 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> EtaLowerBound.compute(points, List.of(NEST_A), 10.0, -1.0));
    }
}
