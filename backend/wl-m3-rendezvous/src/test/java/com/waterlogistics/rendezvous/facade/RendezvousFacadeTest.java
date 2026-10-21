package com.waterlogistics.rendezvous.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.handover.HandoverParams;
import com.waterlogistics.rendezvous.handover.HandoverSample;
import com.waterlogistics.rendezvous.port.SnapshotUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 门面与求解编排单测——验证<b>端口反转接线</b>（stub 端口供数）＋ 版本一致性闸门
 * ＋ 门面航迹生成透传（《22-接口契约》§5 第 3／5 步、《81-B组算法方案》§2.1 硬要求 1）。
 *
 * <p>几何夹具沿用求解器单测：巢在船起点正西 10 km，船东行 5 m/s、机 15 m/s →
 * 手算 τ*＝1000 s。
 */
class RendezvousFacadeTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final double LAT0 = 30.0;
    private static final double LON0 = 121.0;
    private static final double GAP_M = 10_000.0;
    private static final String MMSI = "413000001";
    private static final String VERSION = "PV-1";

    /** 正常路径：stub 端口供数 → 手算会面时刻命中，版本与组合标识透传。 */
    @Test
    void solve_shouldSolveThroughStubbedPort() {
        StubSnapshotPort port = new StubSnapshotPort();
        port.put(snapshot());
        RendezvousFacade facade = new RendezvousFacade(port, droneId -> null);

        B_RendezvousSolution res = facade.solve(command(VERSION));

        assertTrue(res.hasSolution(), "供数后应得解");
        assertNear(NOW + 1_000_000L, res.meetTMin(), 100, "手算 τ*＝1000 s");
        assertEquals("combo-1", res.comboId(), "组合标识透传");
        assertEquals(VERSION, res.snapshotVersion(), "快照版本透传");
        assertEquals(LON0 + 5_000 / mPerDegLon(LAT0), res.meetLon(), 2e-5, "会面点经度（手算）");
    }

    /** 版本闸门：期望版本与快照实际版本不一致 → 拒绝求解，异常信息带双方版本。 */
    @Test
    void solve_shouldReject_whenVersionMismatches() {
        StubSnapshotPort port = new StubSnapshotPort();
        port.put(snapshot());
        RendezvousFacade facade = new RendezvousFacade(port, droneId -> null);

        SnapshotVersionMismatchException ex = assertThrows(
                SnapshotVersionMismatchException.class,
                () -> facade.solve(command("PV-OLD")),
                "版本不一致必须拒绝求解（不一致的轨迹不得进入本轮调度）");

        assertTrue(ex.getMessage().contains("PV-OLD") && ex.getMessage().contains(VERSION),
                "异常信息带期望与实际两个版本：" + ex.getMessage());
    }

    /** 版本闸门 fail-closed：期望版本缺失在命令构造期即拒绝；快照版本缺失由服务层拒绝。 */
    @Test
    void solve_shouldReject_whenVersionMissing() {
        List<ShipPoint> points = new ArrayList<>();
        points.add(new ShipPoint(NOW, LON0, LAT0, 90.0, 0.0, 0.0));
        points.add(new ShipPoint(NOW + 10_000L, LON0, LAT0, 90.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ShipTrackSnapshot(null, MMSI, NOW, 86_400, points),
                "快照版本缺失在模型构造期即拒绝");
        assertThrows(NullPointerException.class,
                () -> command(null),
                "期望版本缺失在命令构造期即拒绝");
    }

    /** 数据缺失：端口查无该船快照 → 拒绝求解（与「算法无解」区分），异常信息带船号。 */
    @Test
    void solve_shouldReject_whenSnapshotAbsent() {
        RendezvousFacade facade = new RendezvousFacade(new StubSnapshotPort(), droneId -> null);

        SnapshotUnavailableException ex = assertThrows(
                SnapshotUnavailableException.class,
                () -> facade.solve(command(VERSION)),
                "查无快照必须拒绝求解");

        assertTrue(ex.getMessage().contains(MMSI), "异常信息带船号：" + ex.getMessage());
    }

    /** 门面航迹生成：与求解共用同一门面，3000 m @ 15 m/s → τ_fine＝200 s。 */
    @Test
    void facade_shouldDelegateTrackGeneration() {
        RendezvousFacade facade = new RendezvousFacade(new StubSnapshotPort(), droneId -> null);

        B_FlyableRoute route = facade.generateTrack(new GenerateRouteCommand(
                "combo-1", "RT-1", lonAt(0), LAT0, lonAt(3_000), LAT0,
                60.0, 15.0, NOW, 5, List.of(), List.of(), 0.05, 0.2));

        assertEquals(200, route.tauFine(), "航迹经门面同一能力：3000/15＝200 s");
        assertTrue(route.routeVersion().startsWith("RV-RT-1-" + NOW), "版本按起点时刻与几何指纹生成");
    }

    /** 门面冲突二查透传：空间远离由包围盒粗筛短路。 */
    @Test
    void facade_shouldDelegateConflictCheck() {
        RendezvousFacade facade = new RendezvousFacade(new StubSnapshotPort(), droneId -> null);
        B_FlyableRoute a = new B_FlyableRoute("RT-A", "RV-A", "combo-1",
                List.of(new Waypoint(121.0, 30.0, 60.0, 15.0, NOW),
                        new Waypoint(121.1, 30.0, 60.0, 15.0, NOW + 600_000L)),
                600, 0.0, 0.0, List.of(), List.of());
        B_FlyableRoute b = new B_FlyableRoute("RT-B", "RV-B", "combo-1",
                List.of(new Waypoint(122.0, 30.0, 60.0, 15.0, NOW),
                        new Waypoint(122.1, 30.0, 60.0, 15.0, NOW + 600_000L)),
                600, 0.0, 0.0, List.of(), List.of());

        B_ConflictResult res = facade.detectConflict(a, b, new ConflictParams(100, 25, 1_000));

        assertFalse(res.conflictFlag(), "空间远离：包围盒粗筛短路");
        assertEquals("RV-A", res.checksumVersion(), "checksumVersion 取被查航迹版本");
    }

    /** 门面起飞时刻回填透传：算式部分只算不定案。 */
    @Test
    void facade_shouldDelegateTakeoffTime() {
        RendezvousFacade facade = new RendezvousFacade(new StubSnapshotPort(), droneId -> null);

        B_TakeoffTimeResult r = facade.computeTakeoffTime(new TakeoffTimeCommand(
                "combo-1", NOW + 230_000L, 200, NOW, 1_000L));

        assertEquals(NOW + 30_000L, r.takeoffT(), "230−200＝30 s，整秒对齐不变");
        assertTrue(r.feasible(), "起飞晚于当前");
    }

    /** 门面末段交接判定透传：未进邻域即判失败（上报 M2）。 */
    @Test
    void facade_shouldDelegateHandoverJudge() {
        RendezvousFacade facade = new RendezvousFacade(new StubSnapshotPort(), droneId -> null);
        List<HandoverSample> samples = List.of(
                new HandoverSample(NOW, 121.01, 30.0, 121.0, 30.0, 5.0, false),
                new HandoverSample(NOW + 1_000L, 121.0099, 30.0, 121.0, 30.0, 5.0, false));

        B_HandoverConfirmation r = facade.judgeHandover(new HandoverCommand(
                "task-1", "UAV-01", 121.0, 30.0, NOW, samples,
                new HandoverParams(100, 50, 2, 10, 10, 0.01)));

        assertEquals(HandoverResult.FAILED, r.handoverResult(), "未进邻域 → 失败上报");
        assertNull(r.actualMeetLon(), "未完成接近：无实际会面点");
    }

    // ===== 夹具 =====

    private static RendezvousCommand command(String expectedVersion) {
        return new RendezvousCommand(
                "combo-1", MMSI, expectedVersion,
                nestLon(), LAT0, 15.0, new Wind(0, 0, 0),
                NOW, NOW + 3_600_000L, 30, 0.01, 50);
    }

    /** 船东行 5 m/s 的匀速直线船迹（400 点 × 10 s） */
    private static ShipTrackSnapshot snapshot() {
        double mPerDegLon = mPerDegLon(LAT0);
        List<ShipPoint> points = new ArrayList<>(400);
        for (int i = 0; i < 400; i++) {
            double dist = 5.0 * i * 10;
            points.add(new ShipPoint(NOW + (long) i * 10_000L,
                    LON0 + dist / mPerDegLon, LAT0, 90.0, 0.0, 0.0));
        }
        return new ShipTrackSnapshot(VERSION, MMSI, NOW, 86_400, points);
    }

    private static double nestLon() {
        return LON0 - GAP_M / mPerDegLon(LAT0);
    }

    private static double lonAt(double eastM) {
        return LON0 + eastM / mPerDegLon(LAT0);
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

    /** 固定供数的快照端口桩——模拟 M2 侧实现（生产实现由适配器接预测服务） */
    private static final class StubSnapshotPort implements TrajectorySnapshotPort {

        private final Map<String, ShipTrackSnapshot> snapshots = new HashMap<>();

        void put(ShipTrackSnapshot snapshot) {
            snapshots.put(snapshot.shipMmsi(), snapshot);
        }

        @Override
        public ShipTrackSnapshot latest(String shipMmsi) {
            return snapshots.get(shipMmsi);
        }
    }
}
