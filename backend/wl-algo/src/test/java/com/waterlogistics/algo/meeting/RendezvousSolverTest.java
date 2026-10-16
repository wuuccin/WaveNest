package com.waterlogistics.algo.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.NoSolutionReason;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会面点求解单测——「真算法」判据：改船速／航向／机速／风，结果跟着变
 * （《43-编码分工说明》§6.3 验收、《81-B组算法方案》§3.1）。
 *
 * <p>几何夹具：机巢在船起点<b>正西 10 km</b>（同一纬线），船沿纬线匀速直行——
 * 局部等距投影下距离与追逐时长为精确值，全部期望值直接手算，容差只吃插值、迭代与取整误差。
 */
class RendezvousSolverTest {

    /** 求解起点（任意确定时刻） */
    private static final long NOW = 1_700_000_000_000L;

    /** 作业纬线 */
    private static final double LAT0 = 30.0;

    /** 船起点经度 */
    private static final double LON0 = 121.0;

    /** 巢与船起点间距，米 */
    private static final double GAP_M = 10_000.0;

    /** 迭代收敛阈值 ε_t 与最大次数（由测试注入，同实现口径） */
    private static final double EPSILON_SEC = 0.01;

    private static final int MAX_ITER = 50;

    // ===== 手算对照与真算法判据 =====

    /**
     * 基准场景：船东行 5 m/s、机 15 m/s、间距 10 km，同向闭合 10 m/s → τ*＝1000 s。
     */
    @Test
    void straightChase_shouldMeetAtHandCalculatedTimeAndPoint() {
        B_RendezvousSolution res = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);

        assertTrue(res.hasSolution(), "同向追及应有解");
        assertNear(NOW + 1_000_000L, res.meetTMin(), 100, "会面时刻＝手算 τ*");
        assertEquals(res.meetTMin(), res.shipArriveT(), "船到达时刻＝会面时刻");
        assertEquals(res.meetTMin(), res.droneArriveT(), "机到达时刻＝会面时刻");
        assertEquals(res.meetTMin() + 30_000L, res.meetTMax(), "窗口上界＝t*＋余量（30 s）");
        assertNear(1000, res.tauCoarse(), 1, "理论飞行时长＝手算 1000 s");
        // 会面点＝船在 t* 的位置：起点东移 5×1000 m
        assertEquals(LON0 + 5_000 / mPerDegLon(LAT0), res.meetLon(), 2e-5, "会面点经度");
        assertEquals(LAT0, res.meetLat(), 1e-9, "会面点纬度（同纬线）");
        assertEquals("PV-1", res.snapshotVersion(), "快照版本回填");
    }

    /**
     * 改船速：8 m/s → 闭合 7 m/s → τ*≈1428.57 s，会面点更远。
     */
    @Test
    void fasterShip_shouldPushMeetPointLaterAndFarther() {
        B_RendezvousSolution slow = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);
        B_RendezvousSolution fast = solve(
                snapshot(LON0, LAT0, 90, 8, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);

        assertNear(NOW + 1_428_571L, fast.meetTMin(), 300, "改船速 → 会面时刻≈1428.6 s");
        assertTrue(fast.meetTMin() > slow.meetTMin(), "船更快 → 同向追及更晚");
        assertTrue(fast.meetLon() > slow.meetLon(), "船更快 → 会面点更远");
    }

    /**
     * 改航向：对向（270°）接近速度 15＋5＝20 m/s → τ*＝500 s，会面点＝起点西移 2.5 km。
     */
    @Test
    void reversedCourse_shouldPullMeetPointEarlier() {
        B_RendezvousSolution eastbound = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);
        B_RendezvousSolution westbound = solve(
                snapshot(LON0, LAT0, 270, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);

        assertNear(NOW + 500_000L, westbound.meetTMin(), 100, "改航向 → 会面时刻＝500 s");
        assertNear(500, westbound.tauCoarse(), 1, "对向理论飞行时长＝500 s");
        assertEquals(LON0 - 2_500 / mPerDegLon(LAT0), westbound.meetLon(), 2e-5, "对向会面点经度");
        assertTrue(westbound.meetTMin() < eastbound.meetTMin(), "对向比同向先相遇");
    }

    /**
     * 改机速：20 m/s → 闭合 15 m/s → τ*≈666.67 s。
     */
    @Test
    void fasterDrone_shouldPullMeetPointEarlier() {
        B_RendezvousSolution base = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);
        B_RendezvousSolution faster = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 20, noWind(), NOW + 3_600_000L, 30);

        assertNear(NOW + 666_667L, faster.meetTMin(), 300, "改机速 → 会面时刻≈666.7 s");
        assertTrue(faster.meetTMin() < base.meetTMin(), "机更快 → 相遇更早");
    }

    /**
     * 改风：顺风缩短、逆风拉长、纯侧风不改沿航向速度（来向口径，见 {@link Wind}）。
     */
    @Test
    void wind_shouldChangeEffectiveSpeedAlongBearing() {
        // 顺风（来向 270°＝西风）：v＝15＋5＝20 → τ*＝10000/(20−5)≈666.67 s
        B_RendezvousSolution tail = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, new Wind(5, 270, 0), NOW + 3_600_000L, 30);
        assertNear(NOW + 666_667L, tail.meetTMin(), 500, "顺风提前相遇");
        // 逆风（来向 90°＝东风）：v＝15−5＝10 → τ*＝10000/(10−5)＝2000 s
        B_RendezvousSolution head = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, new Wind(5, 90, 0), NOW + 3_600_000L, 30);
        assertNear(NOW + 2_000_000L, head.meetTMin(), 500, "逆风推迟相遇");
        // 纯侧风（来向 0°＝北风）：沿航向分量≈0 → 与无风一致
        B_RendezvousSolution cross = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, new Wind(5, 0, 0), NOW + 3_600_000L, 30);
        assertNear(NOW + 1_000_000L, cross.meetTMin(), 1_500, "侧风不改变沿航向速度");
    }

    /**
     * 追不上：船速 ≥ 机速且同向远离 → 无实数解，短路淘汰（《32》§4.3）。
     */
    @Test
    void shipAtLeastAsFastAsDrone_shouldBeUncatchable() {
        for (double sog : new double[] {15, 20}) {
            B_RendezvousSolution res = solve(
                    snapshot(LON0, LAT0, 90, sog, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);
            assertFalse(res.hasSolution(), "船速 " + sog + " ≥ 机速，同向追不上");
            assertEquals(NoSolutionReason.CANNOT_CATCH, res.noSolutionReason(), "原因＝追不上");
            assertNull(res.meetLon(), "无解时会面字段为 null");
            assertNull(res.meetLat(), "无解时会面字段为 null");
            assertNull(res.meetTMin(), "无解时会面字段为 null");
            assertNull(res.meetTMax(), "无解时会面字段为 null");
            assertNull(res.tauCoarse(), "无解时不产理论飞行时长");
            assertEquals("PV-1", res.snapshotVersion(), "无解也回填快照版本（追溯用）");
        }
    }

    /**
     * 强逆风：有效速度 ≤ 0 → 直接判追不上（不进入求根）。
     */
    @Test
    void headwindStrongerThanCruise_shouldBeUncatchable() {
        B_RendezvousSolution res = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, new Wind(20, 90, 0), NOW + 3_600_000L, 30);

        assertFalse(res.hasSolution(), "顶风风速大于巡航速度，无有效速度");
        assertEquals(NoSolutionReason.CANNOT_CATCH, res.noSolutionReason());
    }

    /**
     * 入参退化与数据覆盖不足：空点列／时限已过＝其他；点列不够长＝可求解窗口内无根（追不上）。
     */
    @Test
    void degenerateInputs_shouldFailFast() {
        // 空点列
        B_RendezvousSolution empty = RendezvousSolver.solve(new RendezvousSolveRequest(
                "combo-1", nestLon(), LAT0, 15, noWind(),
                new ShipTrackSnapshot("PV-1", "413000001", NOW, 3600, List.of()), null,
                NOW, NOW + 3_600_000L, 30, EPSILON_SEC, MAX_ITER));
        assertFalse(empty.hasSolution(), "空点列＝入参退化");
        assertEquals(NoSolutionReason.OTHER, empty.noSolutionReason());

        // 允许时限已过（deadline ≤ now）
        B_RendezvousSolution expired = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW, 30);
        assertFalse(expired.hasSolution(), "时限≤当前＝入参退化");
        assertEquals(NoSolutionReason.OTHER, expired.noSolutionReason());

        // 点列只覆盖 190 s，追及需 1000 s → 窗口截到末点后仍无根
        B_RendezvousSolution shortCover = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 20), 15, noWind(), NOW + 3_600_000L, 30);
        assertFalse(shortCover.hasSolution(), "数据覆盖不足：可求解窗口内无根");
        assertEquals(NoSolutionReason.CANNOT_CATCH, shortCover.noSolutionReason());
    }

    /**
     * 纯函数性：同输入同输出（《81》§2.4 硬约束 1），结果标识确定性生成。
     */
    @Test
    void sameInput_shouldProduceIdenticalResult() {
        B_RendezvousSolution a = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);
        B_RendezvousSolution b = solve(
                snapshot(LON0, LAT0, 90, 5, 10, 400), 15, noWind(), NOW + 3_600_000L, 30);

        assertEquals(a, b, "同输入同输出");
        assertTrue(a.solutionId().startsWith("RS-combo-1-PV-1-"), "结果标识确定性生成");
    }

    /** 无解原因枚举的中文口径（《81》§2.3）。 */
    @Test
    void noSolutionReason_shouldRoundTripChineseValues() {
        assertEquals("追不上", NoSolutionReason.CANNOT_CATCH.getValue());
        assertEquals("不可达", NoSolutionReason.UNREACHABLE.getValue());
        assertEquals(NoSolutionReason.CANNOT_CATCH, NoSolutionReason.fromValue("追不上"));
    }

    // ===== 夹具与断言辅助 =====

    /**
     * 匀速直线船迹：从 {@code (lon0, lat0)} 起、按航向 {@code cogDeg}（0° 正北）与
     * 航速 {@code sogMs} 直行，步长 {@code stepSec}、点数 {@code count}。
     */
    private static ShipTrackSnapshot snapshot(double lon0, double lat0,
            double cogDeg, double sogMs, int stepSec, int count) {
        double rad = Math.toRadians(cogDeg);
        double mPerDegLon = mPerDegLon(lat0);
        List<ShipPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long t = NOW + (long) i * stepSec * 1000L;
            double dist = sogMs * i * stepSec;
            points.add(new ShipPoint(
                    t,
                    lon0 + dist * Math.sin(rad) / mPerDegLon,
                    lat0 + dist * Math.cos(rad) / mPerDeg(),
                    cogDeg,
                    0.0,
                    0.0));
        }
        return new ShipTrackSnapshot("PV-1", "413000001", NOW, 86_400, points);
    }

    private static B_RendezvousSolution solve(ShipTrackSnapshot snap, double cruiseMs,
            Wind wind, long deadlineMs, int marginSec) {
        return RendezvousSolver.solve(new RendezvousSolveRequest(
                "combo-1", nestLon(), LAT0, cruiseMs, wind, snap, null,
                NOW, deadlineMs, marginSec, EPSILON_SEC, MAX_ITER));
    }

    private static Wind noWind() {
        return new Wind(0, 0, 0);
    }

    /** 每度纬差的米数（与 {@code GeoApprox} 同一近似） */
    private static double mPerDeg() {
        return Math.PI * 6_371_000.0 / 180.0;
    }

    private static double mPerDegLon(double lat) {
        return mPerDeg() * Math.cos(Math.toRadians(lat));
    }

    /** 巢：船起点正西 10 km，同一纬线 */
    private static double nestLon() {
        return LON0 - GAP_M / mPerDegLon(LAT0);
    }

    private static void assertNear(long expected, long actual, long toleranceMs, String message) {
        assertTrue(Math.abs(expected - actual) <= toleranceMs,
                message + "（期望 " + expected + "±" + toleranceMs + " ms，实际 " + actual + "）");
    }

    private static void assertNear(int expected, int actual, int tolerance, String message) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                message + "（期望 " + expected + "±" + tolerance + "，实际 " + actual + "）");
    }
}
