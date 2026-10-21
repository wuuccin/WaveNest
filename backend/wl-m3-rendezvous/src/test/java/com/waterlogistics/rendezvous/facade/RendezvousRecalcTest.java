package com.waterlogistics.rendezvous.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.DroneContextUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RecalcOptions;
import com.waterlogistics.rendezvous.solver.RecalcStateSnapshot;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcRequest;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 6a 会面点重算入口单测——接收侧形态照《81-B组算法方案》§2.5：droneId 反查（stub 端口）、
 * 版本一致性锚、数据缺失拒绝、船状态时刻即求解起点、状态船位定向有效速度。
 *
 * <p>几何夹具同前：巢在船起点正西 10 km，船东行 5 m/s、机 15 m/s——期望值可手算。
 */
class RendezvousRecalcTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final double LAT0 = 30.0;
    private static final double LON0 = 121.0;
    private static final double GAP_M = 10_000.0;
    private static final String MMSI = "413000001";
    private static final String DRONE = "UAV-01";
    private static final String VERSION = "PV-1";

    /**
     * 正常路径：状态时刻（＋100 s）即求解起点 → τ*＝10500/10＝1050 s，
     * requestId 原样返回、comboId 由反查回填。
     */
    @Test
    void recompute_shouldAnchorOnStateTimeAndReturnRequestId() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 5, 90), context("combo-1", 15.0));

        RendezvousRecalcResult result = facade.recompute(
                request(VERSION, NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                options(new Wind(0, 0, 0), NOW + 3_600_000L));

        assertEquals("req-1", result.requestId(), "requestId 随结果原样返回");
        assertTrue(result.solution().hasSolution(), "同向追及应有解");
        assertNear(NOW + 100_000L + 1_050_000L, result.solution().meetTMin(), 100,
                "以船状态时刻为求解起点：τ*＝1050 s");
        assertNear(1_050, result.solution().tauCoarse(), 1, "理论飞行时长＝1050 s");
        assertEquals(LON0 + 5_750 / mPerDegLon(LAT0), result.solution().meetLon(), 2e-5,
                "会面点＝船在 t* 的位置（起点东移 5.75 km）");
        assertEquals("combo-1", result.solution().comboId(), "comboId 由 droneId 反查回填");
        assertEquals(VERSION, result.solution().snapshotVersion(), "快照版本透传");
    }

    /** 版本一致性锚（《81》§2.5）：期望版本与快照不一致 → 拒绝求解。 */
    @Test
    void recompute_shouldReject_whenVersionMismatches() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 5, 90), context("combo-1", 15.0));

        assertThrows(SnapshotVersionMismatchException.class,
                () -> facade.recompute(
                        request("PV-OLD", NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                        options(new Wind(0, 0, 0), NOW + 3_600_000L)),
                "版本不一致必须拒绝求解");
    }

    /** 版本闸门 fail-closed（重算侧）：快照版本缺失（null）仍由服务层拒绝；期望版本缺失在构造期即拒绝。 */
    @Test
    void recompute_shouldReject_whenVersionMissing() {
        List<ShipPoint> points = new ArrayList<>();
        points.add(new ShipPoint(NOW, LON0, LAT0, 90.0, 0.0, 0.0));
        points.add(new ShipPoint(NOW + 10_000L, LON0, LAT0, 90.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ShipTrackSnapshot(null, MMSI, NOW, 86_400, points),
                "快照版本缺失在模型构造期即拒绝");
        assertThrows(NullPointerException.class,
                () -> request(null, NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                "期望版本缺失在请求构造期即拒绝（《81》§2.5 求解必需）");
    }

    /** 数据缺失：查无该船快照 → 拒绝求解。 */
    @Test
    void recompute_shouldReject_whenSnapshotAbsent() {
        RendezvousFacade facade = facade(null, context("combo-1", 15.0));

        assertThrows(SnapshotUnavailableException.class,
                () -> facade.recompute(
                        request(VERSION, NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                        options(new Wind(0, 0, 0), NOW + 3_600_000L)),
                "查无快照必须拒绝求解");
    }

    /** 数据缺失：反查不到执行机台账 → 拒绝求解，异常信息带机号。 */
    @Test
    void recompute_shouldReject_whenDroneContextAbsent() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 5, 90), null);

        DroneContextUnavailableException ex = assertThrows(DroneContextUnavailableException.class,
                () -> facade.recompute(
                        request(VERSION, NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                        options(new Wind(0, 0, 0), NOW + 3_600_000L)),
                "反查不到台账必须拒绝求解");
        assertTrue(ex.getMessage().contains(DRONE), "异常信息带机号：" + ex.getMessage());
    }

    /** 组合回溯不到：comboId 为 null 不影响求解（《81》§2.5「取不到为 null」）。 */
    @Test
    void recompute_shouldCarryNullComboId_whenTaskNotTraceable() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 5, 90), context(null, 15.0));

        RendezvousRecalcResult result = facade.recompute(
                request(VERSION, NOW + 100_000L, LON0 + 500 / mPerDegLon(LAT0), LAT0),
                options(new Wind(0, 0, 0), NOW + 3_600_000L));

        assertTrue(result.solution().hasSolution(), "组合回溯不到不影响求解");
        assertNull(result.solution().comboId(), "取不到当前任务 → comboId 为 null");
    }

    /**
     * 状态船位定向（故意不一致夹具，只为区分定向来源）：快照船静悬在 (LON0, LAT0)，
     * 状态船位在巢正北 10 km——两处到巢的方位分别正东／正北；以「风来向 90° 风速 5」区分：
     * 正北向 ⇒ v＝15（侧风）；若回落快照 ⇒ v＝10（逆风）→ 用 τ*≈666.7 s 钉住「状态船位定向」生效。
     */
    @Test
    void recompute_shouldBearByStatePosition_notBySnapshotFallback() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 0, 90), context("combo-1", 15.0));
        double stateLon = LON0 - GAP_M / mPerDegLon(LAT0);
        double stateLat = LAT0 + 10_000 / mPerDeg();

        RendezvousRecalcResult result = facade.recompute(
                request(VERSION, NOW, stateLon, stateLat),
                options(new Wind(5, 90, 0), NOW + 3_600_000L));

        assertTrue(result.solution().hasSolution(), "静悬船必可追上");
        assertNear(NOW + 666_667L, result.solution().meetTMin(), 500,
                "定向＝正北 ⇒ 侧风 v＝15 ⇒ τ*＝10000/15≈666.7 s（若按快照回落则为 1000 s）");
    }

    /** 6a 参数敏感性：代表风况变化须让会面时刻随之变化（无风与逆风对比）。 */
    @Test
    void recompute_shouldShiftMeetTimeWithRepresentativeWind() {
        RendezvousFacade facade = facade(snapshot(LON0, LAT0, 5, 90), context("combo-1", 15.0));
        double shipLon = LON0 + 500 / mPerDegLon(LAT0);

        RendezvousRecalcResult calm = facade.recompute(
                request(VERSION, NOW + 100_000L, shipLon, LAT0),
                options(new Wind(0, 0, 0), NOW + 3_600_000L));
        RendezvousRecalcResult headwind = facade.recompute(
                request(VERSION, NOW + 100_000L, shipLon, LAT0),
                options(new Wind(3, 90, 0), NOW + 3_600_000L));

        assertTrue(calm.solution().hasSolution(), "无风工况应有解");
        assertTrue(headwind.solution().hasSolution(), "逆风工况仍应在时限内可追");
        assertTrue(headwind.solution().meetTMin() > calm.solution().meetTMin(),
                "逆风须推迟会面时刻：无风 " + calm.solution().meetTMin()
                        + "，逆风 " + headwind.solution().meetTMin());
    }

    // ===== 夹具 =====

    private static RendezvousFacade facade(ShipTrackSnapshot snapshot, DroneContext context) {
        StubSnapshotPort snapshots = new StubSnapshotPort();
        if (snapshot != null) {
            snapshots.put(snapshot);
        }
        StubDroneContextPort drones = new StubDroneContextPort();
        if (context != null) {
            drones.put(context);
        }
        return new RendezvousFacade(snapshots, drones);
    }

    private static RendezvousRecalcRequest request(String version, long stateT,
            double shipLon, double shipLat) {
        return new RendezvousRecalcRequest("req-1", DRONE, version, MMSI,
                "order-9", "定期重估", NOW,
                new RecalcStateSnapshot(shipLon, shipLat, 90.0, 5.0, stateT,
                        null, null, null, null));
    }

    private static RecalcOptions options(Wind wind, long deadlineMs) {
        return new RecalcOptions(wind, deadlineMs, 30, 0.01, 50);
    }

    private static DroneContext context(String comboId, double cruiseMs) {
        return new DroneContext(DRONE, "NEST-1",
                LON0 - GAP_M / mPerDegLon(LAT0), LAT0, cruiseMs, comboId);
    }

    /** 匀速直线船迹（400 点 × 10 s）：从 (lon0, lat0) 起、按航向与航速直行 */
    private static ShipTrackSnapshot snapshot(double lon0, double lat0, double sogMs, double cogDeg) {
        double rad = Math.toRadians(cogDeg);
        double mpl = mPerDegLon(lat0);
        List<ShipPoint> points = new ArrayList<>(400);
        for (int i = 0; i < 400; i++) {
            double dist = sogMs * i * 10;
            points.add(new ShipPoint(NOW + (long) i * 10_000L,
                    lon0 + dist * Math.sin(rad) / mpl,
                    lat0 + dist * Math.cos(rad) / mPerDeg(),
                    cogDeg, 0.0, 0.0));
        }
        return new ShipTrackSnapshot(VERSION, MMSI, NOW, 86_400, points);
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

    private static void assertNear(int expected, int actual, int tolerance, String message) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                message + "（期望 " + expected + "±" + tolerance + "，实际 " + actual + "）");
    }

    /** 固定供数的快照端口桩 */
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

    /** 固定供数的执行机台账端口桩 */
    private static final class StubDroneContextPort implements DroneContextPort {

        private final Map<String, DroneContext> contexts = new HashMap<>();

        void put(DroneContext context) {
            contexts.put(context.droneId(), context);
        }

        @Override
        public DroneContext resolve(String droneId) {
            return contexts.get(droneId);
        }
    }
}
