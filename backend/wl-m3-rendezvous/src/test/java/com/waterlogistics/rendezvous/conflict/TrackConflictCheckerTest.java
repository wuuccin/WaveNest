package com.waterlogistics.rendezvous.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.rendezvous.trajectory.TrackGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 航迹级冲突二查单测——十字交汇（冲突）／垂直隔离／时间错峰／时间不重叠／空间远离
 * 逐档对照（《81-B组算法方案》§3.4、《43-编码分工说明》§8 ②）。
 *
 * <p>几何夹具：东西向与南北向两条等长正交航迹（半程各 9630 m＝9630/15＝642 s），
 * 速度 15 m/s——交汇时刻、最近距离均可手算。
 */
class TrackConflictCheckerTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final double LAT0 = 30.0;
    private static final double LON0 = 121.0;
    private static final double HALF_M = 9_630.0;
    private static final ConflictParams PARAMS = new ConflictParams(100, 25, 1_000);

    /** 十字交汇、同时到达、同高：采样判冲突，CPA≈0 落在交汇时刻。 */
    @Test
    void crossAtSameTime_shouldFlagConflictWithNearZeroCpa() {
        B_ConflictResult res = TrackConflictChecker.detect(eastTrack(), northTrack(T0, 60.0), PARAMS);

        assertTrue(res.conflictFlag(), "同时到达交点且同高，应判冲突");
        assertNotNull(res.cpaPoint(), "采样路径应产最近接近点");
        assertTrue(res.cpaDistanceM() < 20, "CPA 距离≈0，实际 " + res.cpaDistanceM());
        assertNear(T0 + 642_000L, res.cpaT(), 1_000, "CPA 时刻≈交汇时刻（τ/2＝642 s）");
        assertNotNull(res.conflictSpaceTime(), "冲突时空区间必须给出");
        assertTrue(res.conflictSpaceTime().tBegin() <= T0 + 642_000L
                        && res.conflictSpaceTime().tEnd() >= T0 + 642_000L,
                "冲突窗口应覆盖交汇时刻");
        assertNull(res.conflictSpaceTime().zone(), "机—机无台账区域标识：zone 为 null");
        assertNull(res.detourRoute(), "绕行几何首版留桩");
        assertTrue(res.checksumVersion().startsWith("RV-RT-A-" + T0), "checksumVersion 取被查航迹版本");
    }

    /** 高度相隔 40 m（≥垂直安全间隔 25 m）：垂直箱隙不可能越限，包围盒粗筛直接剔除。 */
    @Test
    void verticalSeparated_shouldNotConflict() {
        B_ConflictResult res = TrackConflictChecker.detect(eastTrack(), northTrack(T0, 100.0), PARAMS);

        assertFalse(res.conflictFlag(), "垂直 40 m 已隔开——双间隔须同时成立");
        assertNull(res.cpaPoint(), "垂直箱隙≥安全间隔：粗筛剔除，不产 CPA");
        assertNull(res.cpaDistanceM(), "同上");
    }

    /** 时间错峰 200 s：仍偶遇但拉开≈2.1 km，不判冲突；CPA 给出（采样路径）。 */
    @Test
    void crossingApart_shouldNotConflictButReportCpa() {
        B_ConflictResult res = TrackConflictChecker.detect(
                eastTrack(), northTrack(T0 + 200_000L, 60.0), PARAMS);

        assertFalse(res.conflictFlag(), "错峰 200 s 已拉开安全距离");
        assertTrue(res.cpaDistanceM() > 2_000, "最近距离≈2.1 km，实际 " + res.cpaDistanceM());
        assertNull(res.conflictSpaceTime(), "无冲突不产时空区间");
    }

    /** 时间窗完全错开：直接短路，不产 CPA。 */
    @Test
    void noTimeOverlap_shouldShortCircuit() {
        B_ConflictResult res = TrackConflictChecker.detect(
                eastTrack(), northTrack(T0 + 1_300_000L, 60.0), PARAMS);

        assertFalse(res.conflictFlag(), "时间窗无重叠不判冲突");
        assertNull(res.cpaPoint(), "时间窗无重叠 → 无 CPA");
    }

    /** 空间远离：包围盒粗筛短路（近零成本剔除），不进入采样。 */
    @Test
    void farApart_shouldBeFilteredByBoundingBox() {
        B_FlyableRoute far = route("RT-C",
                LON0 + 1.0, latAt(-HALF_M), LON0 + 1.0, latAt(HALF_M), 60.0, T0);

        B_ConflictResult res = TrackConflictChecker.detect(eastTrack(), far, PARAMS);

        assertFalse(res.conflictFlag(), "空间远离不应判冲突");
        assertNull(res.cpaPoint(), "粗筛短路不产 CPA");
        assertNull(res.conflictSpaceTime(), "粗筛短路不产时空区间");
    }

    /** 入参校验：空航迹（构造期）、航点不足（检测期）与非法采样步长一律拒绝。 */
    @Test
    void invalidInputs_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new B_FlyableRoute("RT-X", "RV-X", "combo-1",
                        List.of(), 0, 0.0, 0.0, List.of(), List.of()),
                "空航迹构造期即拒绝");

        assertThrows(IllegalArgumentException.class,
                () -> new B_FlyableRoute("RT-X", "RV-X", "combo-1",
                        List.of(new Waypoint(121.0, 30.0, 60.0, 15.0, T0)), 0, 0.0, 0.0, List.of(), List.of()),
                "航点不足 2 构造期即拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> TrackConflictChecker.detect(eastTrack(), northTrack(T0, 60.0),
                                                  new ConflictParams(100, 25, 0)), "采样步长非正应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ConflictParams(Double.POSITIVE_INFINITY, 25, 1_000),
                "无穷大安全间隔应拒绝（防判据恒真——Infinity 不得穿透）");
    }

    /** 航点时刻非严格递增：前置校验拒绝（时间窗与线性插值均依赖该不变量）。 */
    @Test
    void unsortedWaypoints_shouldBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new B_FlyableRoute("RT-D", "RV-D", "combo-1",
                        List.of(new Waypoint(121.0, 30.0, 60.0, 15.0, T0),
                                new Waypoint(121.1, 30.0, 60.0, 15.0, T0)),
                        0, 0.0, 0.0, List.of(), List.of()),
                "航点时刻非严格递增须在构造期拒绝");
    }

    /** 超大采样步长：推进须防溢出（旧写法 t+step 溢出为负会死循环）——快速走到 tEnd 收尾。 */
    @Test
    void hugeSampleStep_shouldTerminate() {
        B_ConflictResult res = TrackConflictChecker.detect(
                eastTrack(), northTrack(T0, 60.0), new ConflictParams(100, 25, 600_000L));

        assertFalse(res.conflictFlag(), "仅两端点采样且相距远：不判冲突");
        assertThrows(IllegalArgumentException.class,
                () -> new ConflictParams(100, 25, Long.MAX_VALUE), "超出合理上界的采样步长应拒绝");
    }

    // ===== 夹具 =====

    /** 东西向基准航迹：宽 2×9630 m，经 LON0 中点，速度 15 m/s、5 航点 */
    private static B_FlyableRoute eastTrack() {
        return route("RT-A", lonAt(-HALF_M), LAT0, lonAt(HALF_M), LAT0, 60.0, T0);
    }

    /** 南北向正交航迹：跨 LON0 垂线，经 (LON0, LAT0) 中点 */
    private static B_FlyableRoute northTrack(long startMs, double altM) {
        return route("RT-B", LON0, latAt(-HALF_M), LON0, latAt(HALF_M), altM, startMs);
    }

    private static B_FlyableRoute route(String id, double sLon, double sLat, double eLon, double eLat,
            double altM, long startMs) {
        return TrackGenerator.generate(new GenerateRouteCommand(
                "combo-1", id, sLon, sLat, eLon, eLat, altM, 15.0, startMs, 5,
                List.of(), List.of(), 0.0, 0.0));
    }

    private static double lonAt(double refEastM) {
        return LON0 + refEastM / mPerDegLon(LAT0);
    }

    private static double latAt(double refNorthM) {
        return LAT0 + refNorthM / mPerDeg();
    }

    private static double mPerDeg() {
        return Math.PI * 6_371_000.0 / 180.0;
    }

    private static double mPerDegLon(double lat) {
        return mPerDeg() * Math.cos(Math.toRadians(lat));
    }

    private static void assertNear(long expected, long actual, long toleranceMs, String message) {
        assertTrue(Math.abs(expected - actual) <= toleranceMs,
                message + "（期望 " + expected + "±" + toleranceMs + " ms，实际 " + actual + "）");
    }
}
