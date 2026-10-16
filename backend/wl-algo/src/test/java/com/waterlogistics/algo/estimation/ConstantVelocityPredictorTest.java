package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConstantVelocityPredictor} 单测。
 *
 * <p>本组测试的用意不只是「跑通」，而是<b>证明结果由输入决定、不是写死的</b>：
 * 改航速、改航向，输出必须跟着变（《编码分工表》§七 第 7 条对真算法的判据，
 * 简化版同样据此自证）。
 */
class ConstantVelocityPredictorTest {

    /** 测试专用参数：外推 600 s、每 60 s 一点（11 点）。 */
    private static final PredictionParams PARAMS =
            new PredictionParams(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);

    private static final double T0 = 1_760_000_000_000L;
    private static final double LON = 121.4700;
    private static final double LAT = 30.5000;

    @Test
    @DisplayName("点个数由参数决定：horizonSec/stepSec + 1")
    void pointCount_shouldFollowParams() {
        PredictionResult result = ConstantVelocityPredictor.predict("413000001", LON, LAT, 3.0, 45.0, (long) T0, PARAMS);
        assertEquals(11, result.points().size());
        assertEquals((long) T0, result.points().get(0).tMs(), "首点为 τ=0 的当前点");
        assertEquals((long) T0 + 600_000L, result.lastTMs());
    }

    @Test
    @DisplayName("正北 1 m/s 走 600 s，应北移约 600 m、经度基本不变")
    void northbound_shouldMoveNorthOnly() {
        PredictionResult result = ConstantVelocityPredictor.predict("413000001", LON, LAT, 1.0, 0.0, (long) T0, PARAMS);
        EstimatedPoint last = result.points().get(10);

        double deltaNorthM = (last.lat() - LAT) * ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        assertEquals(600.0, deltaNorthM, 1.0, "正北 1 m/s × 600 s ≈ 600 m");
        assertEquals(LON, last.lon(), 1e-9, "正北航行不应改变经度");
    }

    @Test
    @DisplayName("正东 1 m/s 走 600 s，应东移约 600 m、纬度基本不变")
    void eastbound_shouldMoveEastOnly() {
        PredictionResult result = ConstantVelocityPredictor.predict("413000001", LON, LAT, 1.0, 90.0, (long) T0, PARAMS);
        EstimatedPoint last = result.points().get(10);

        double metersPerDegLon =
                ConstantVelocityPredictor.METERS_PER_DEG_LAT * Math.cos(Math.toRadians(LAT));
        double deltaEastM = (last.lon() - LON) * metersPerDegLon;
        assertEquals(600.0, deltaEastM, 1.0, "正东 1 m/s × 600 s ≈ 600 m");
        assertEquals(LAT, last.lat(), 1e-9, "正东航行不应改变纬度");
    }

    @Test
    @DisplayName("★ 改航速，预测终点距离跟着变（非写死）")
    void changingSpeed_shouldChangeReach() {
        double slow = planarDistanceM(
                ConstantVelocityPredictor.predict("413000001", LON, LAT, 1.0, 45.0, (long) T0, PARAMS));
        double fast = planarDistanceM(
                ConstantVelocityPredictor.predict("413000001", LON, LAT, 4.0, 45.0, (long) T0, PARAMS));

        assertTrue(fast > slow * 3.5,
                "4 m/s 的 600 s 位移应约为 1 m/s 的 4 倍，实测 " + slow + " → " + fast);
    }

    @Test
    @DisplayName("★ 改航向，预测方位跟着变（非写死）")
    void changingCourse_shouldChangeBearing() {
        PredictionResult north = ConstantVelocityPredictor.predict("413000001", LON, LAT, 2.0, 0.0, (long) T0, PARAMS);
        PredictionResult east = ConstantVelocityPredictor.predict("413000001", LON, LAT, 2.0, 90.0, (long) T0, PARAMS);

        assertNotEquals(north.courseDeg(), east.courseDeg(), "主轴方位应随航向改变");
        assertEquals(0.0, north.courseDeg(), 1e-9);
        assertEquals(90.0, east.courseDeg(), 1e-9);

        // 同一 τ 下两点应明显分离（方位不同 → 终点位置不同）
        EstimatedPoint n = north.points().get(10);
        EstimatedPoint e = east.points().get(10);
        double separationM = Math.hypot(
                (e.lon() - n.lon()) * ConstantVelocityPredictor.METERS_PER_DEG_LAT,
                (e.lat() - n.lat()) * ConstantVelocityPredictor.METERS_PER_DEG_LAT);
        assertTrue(separationM > 800.0, "正北与正东 600 s 后应相距约 1697 m，实测 " + separationM);
    }

    @Test
    @DisplayName("σ 随时间单调增，且横向小于沿航道；半轴是 2σ")
    void sigma_shouldGrowMonotonically() {
        List<EstimatedPoint> points =
                ConstantVelocityPredictor.predict("413000001", LON, LAT, 3.0, 30.0, (long) T0, PARAMS).points();

        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).sigmaAlongM() > points.get(i - 1).sigmaAlongM(), "沿航道 σ 应递增");
            assertTrue(points.get(i).sigmaCrossM() > points.get(i - 1).sigmaCrossM(), "横向 σ 应递增");
            assertTrue(points.get(i).sigmaCrossM() < points.get(i).sigmaAlongM(), "横向 σ 应小于沿航道 σ");
        }

        EstimatedPoint last = points.get(points.size() - 1);
        assertEquals(2.0 * last.sigmaAlongM(), last.semiMajorM(), 1e-9, "半长轴 = 2σ（标准差，非方差）");
        assertEquals(2.0 * last.sigmaCrossM(), last.semiMinorM(), 1e-9, "半短轴 = 2σ");
    }

    @Test
    @DisplayName("静止船不外推出虚假位移")
    void stationaryShip_shouldNotDrift() {
        List<EstimatedPoint> points =
                ConstantVelocityPredictor.predict("413000001", LON, LAT, 0.0, 123.0, (long) T0, PARAMS).points();

        for (EstimatedPoint point : points) {
            assertEquals(LON, point.lon(), 1e-12);
            assertEquals(LAT, point.lat(), 1e-12);
        }
    }

    @Test
    @DisplayName("节 → m/s 换算用 1852/3600")
    void knotsToMs_shouldUseNauticalMile() {
        assertEquals(1852.0 / 3600.0, ConstantVelocityPredictor.KNOT_TO_MS, 1e-12);
        assertEquals(5.144444, ConstantVelocityPredictor.knotsToMs(10.0), 1e-6, "10 节 ≈ 5.144 m/s");
    }

    @Test
    @DisplayName("输入越界一律抛异常，不静默兜底")
    void invalidInput_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> ConstantVelocityPredictor.predict("413000001", LON, 100.0, 1.0, 0.0, (long) T0, PARAMS));
        assertThrows(IllegalArgumentException.class,
                () -> ConstantVelocityPredictor.predict("413000001", 200.0, LAT, 1.0, 0.0, (long) T0, PARAMS));
        assertThrows(IllegalArgumentException.class,
                () -> ConstantVelocityPredictor.predict("413000001", LON, LAT, -1.0, 0.0, (long) T0, PARAMS));
        assertThrows(IllegalArgumentException.class,
                () -> ConstantVelocityPredictor.predict("  ", LON, LAT, 1.0, 0.0, (long) T0, PARAMS));
    }

    @Test
    @DisplayName("航向归一到 [0,360)：-90° 等价于 270°")
    void course_shouldNormalize() {
        PredictionResult result = ConstantVelocityPredictor.predict("413000001", LON, LAT, 1.0, -90.0, (long) T0, PARAMS);
        assertEquals(270.0, result.courseDeg(), 1e-9);
    }

    /** 起点到预测终点的平面近似距离，m。 */
    private static double planarDistanceM(PredictionResult result) {
        EstimatedPoint last = result.points().get(result.points().size() - 1);
        double dNorth = (last.lat() - LAT) * ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        double dEast = (last.lon() - LON) * ConstantVelocityPredictor.METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(LAT));
        return Math.hypot(dNorth, dEast);
    }
}
