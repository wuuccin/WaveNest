package com.waterlogistics.algo.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 经纬度局部米制近似的基础契约——跨 ±180° 经线的短侧距离、入参越界拒绝与重合点方位。
 */
class GeoApproxTest {

    /** 经差跨日界线：1° 弧长应按短侧计算，而不是整球跨度。 */
    @Test
    void distanceM_shouldTakeShortSideAcrossAntimeridian() {
        double d = GeoApprox.distanceM(-179.5, 0.0, 179.5, 0.0);
        assertEquals(GeoApprox.METERS_PER_DEG, d, 1e-6, "跨 ±180° 的 1° 经差应约等于 1°×πR/180");
    }

    /** 经差恰好 180°：归一化边界仍取半周，不出现整球跨度。 */
    @Test
    void distanceM_shouldHandleHalfTurnBoundary() {
        double d = GeoApprox.distanceM(0.0, 0.0, 180.0, 0.0);
        assertEquals(Math.PI * 6_371_000.0, d, 1e-3, "半周经差＝πR");
    }

    /** 越界或非有限坐标一律拒绝。 */
    @Test
    void distanceM_shouldRejectInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> GeoApprox.distanceM(0.0, 91.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> GeoApprox.distanceM(Double.NaN, 0.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> GeoApprox.bearingDeg(0.0, 0.0, 181.0, 0.0));
    }

    /** 重合点方位无定义：按约定返回正北 0°。 */
    @Test
    void bearingDeg_shouldReturnZeroForCoincidentPoints() {
        assertEquals(0.0, GeoApprox.bearingDeg(121.0, 30.0, 121.0, 30.0), 0.0);
    }

    /** 正东方位＝90°，正北＝0°（0° 正北、顺时针）。 */
    @Test
    void bearingDeg_shouldFollowCompassConvention() {
        assertEquals(90.0, GeoApprox.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-9);
        assertEquals(0.0, GeoApprox.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9);
        assertTrue(GeoApprox.METERS_PER_DEG > 111_000.0, "米/度常量供全工程复用");
    }
}
