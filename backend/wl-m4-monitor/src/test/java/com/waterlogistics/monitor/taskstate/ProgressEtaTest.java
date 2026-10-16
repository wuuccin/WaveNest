package com.waterlogistics.monitor.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.measurement.RouteMeasurement;
import org.junit.jupiter.api.Test;

/**
 * {@link ProgressEta} 单测。
 *
 * <p>按《10-16 清单》§3.1 之三「进度与 ETA 计算（简化）」的完成判据：
 * 算术正确、复用 F 组剩余距离不改口径、阈值参数化、不可算时<b>不拿直线距离兜底</b>。
 */
class ProgressEtaTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final double MIN_SPEED = 1.0;

    private static RouteMeasurement.Remaining computable(double distanceM, String quality) {
        return new RouteMeasurement.Remaining(true, distanceM, null, 7L, 3L, quality);
    }

    /** 正常算：ETA ＝ 当前时刻 ＋ 剩余距离 ÷ 地速 */
    @Test
    void etaEqualsRemainingOverGroundSpeed() {
        ProgressEta eta = ProgressEta.estimate(computable(1200.0, "observed_geometry"), 15.0, MIN_SPEED, NOW);
        assertTrue(eta.computable());
        assertEquals(NOW + 80_000L, eta.etaMs(), "1200m ÷ 15m/s ＝ 80s");
        assertNull(eta.reason());
        assertEquals(15.0, eta.groundSpeedMs());
    }

    /** ETA 随剩余距离线性变化：距离翻倍，到达时刻随之翻倍 */
    @Test
    void etaGrowsWithRemainingDistance() {
        long near = ProgressEta.estimate(computable(1000.0, "observed_geometry"), 10.0, MIN_SPEED, NOW).etaMs();
        long far = ProgressEta.estimate(computable(2000.0, "observed_geometry"), 10.0, MIN_SPEED, NOW).etaMs();
        assertEquals(NOW + 100_000L, near);
        assertEquals(NOW + 200_000L, far);
    }

    /** 阈值参数化：同一份输入，地速下限 5 与 2 给出不同判定 */
    @Test
    void judgementFollowsInjectedSpeedFloor() {
        RouteMeasurement.Remaining remaining = computable(1000.0, "observed_geometry");
        assertFalse(ProgressEta.estimate(remaining, 3.0, 5.0, NOW).computable(),
                "地速 3 低于下限 5，应判不可算");
        assertTrue(ProgressEta.estimate(remaining, 3.0, 2.0, NOW).computable(),
                "下限放宽到 2 后同一输入应可算——阈值须由调用方注入");
    }

    /** 返航／偏航：F 组判不可算时，原样透传原因且<b>绝不</b>给出 ETA */
    @Test
    void remainingNotComputableIsPassedThroughWithoutFallback() {
        RouteMeasurement.Remaining returning =
                new RouteMeasurement.Remaining(false, null, "RETURN_OR_OFF_ROUTE_USE_E10_B16", 7L, null,
                        "not_computable");
        ProgressEta eta = ProgressEta.estimate(returning, 20.0, MIN_SPEED, NOW);
        assertFalse(eta.computable(), "返航或偏航时不得拿直线距离冒充航程");
        assertEquals(ProgressEta.REASON_REMAINING_NOT_COMPUTABLE, eta.reason());
        assertNull(eta.etaMs());
        assertEquals("not_computable", eta.calculationQuality());
    }

    /** 地速不可用：非有限、零、负均判不可算 */
    @Test
    void unusableGroundSpeedIsRejected() {
        RouteMeasurement.Remaining remaining = computable(1000.0, "observed_geometry");
        assertEquals(ProgressEta.REASON_SPEED_UNAVAILABLE,
                ProgressEta.estimate(remaining, Double.NaN, MIN_SPEED, NOW).reason());
        assertEquals(ProgressEta.REASON_SPEED_UNAVAILABLE,
                ProgressEta.estimate(remaining, 0.0, MIN_SPEED, NOW).reason());
        assertEquals(ProgressEta.REASON_SPEED_UNAVAILABLE,
                ProgressEta.estimate(remaining, -3.0, MIN_SPEED, NOW).reason());
    }

    /** 几何品质标签原样透传 F 组口径，本类不另立标签 */
    @Test
    void calculationQualityIsPassedThrough() {
        assertEquals("imputed_geometry", ProgressEta
                .estimate(computable(500.0, "imputed_geometry"), 10.0, MIN_SPEED, NOW).calculationQuality());
        assertEquals("observed_geometry", ProgressEta
                .estimate(computable(500.0, "observed_geometry"), 10.0, MIN_SPEED, NOW).calculationQuality());
    }

    /** remaining 缺失必须报错，不得凭空造 ETA */
    @Test
    void missingRemainingIsRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProgressEta.estimate(null, 10.0, MIN_SPEED, NOW));
        assertTrue(thrown.getMessage().contains("remaining"),
                "异常信息须点名缺失的入参 remaining，便于定位：" + thrown.getMessage());
    }

    /** 构造期自守：不可算却带着 etaMs 出去，下游会误当可用值 */
    @Test
    void constructorRejectsInconsistentState() {
        IllegalArgumentException inconsistent = assertThrows(IllegalArgumentException.class,
                () -> new ProgressEta(false, 123L, ProgressEta.REASON_SPEED_UNAVAILABLE, 1.0, null,
                        "observed_geometry"));
        assertTrue(inconsistent.getMessage().contains("不可算时不得给出 etaMs"),
                "须点明是「不可算却带 etaMs」这一不一致，便于定位：" + inconsistent.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new ProgressEta(true, null, null, 1.0, 10.0, "observed_geometry"));
        assertTrue(missing.getMessage().contains("calculationQuality"),
                "须点名缺失的字段名，便于定位：" + missing.getMessage());
    }
}