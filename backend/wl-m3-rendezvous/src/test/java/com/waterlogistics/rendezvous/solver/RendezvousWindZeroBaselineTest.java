package com.waterlogistics.rendezvous.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 风＝0 会面基线（10-18 修正日）——固定输入（船速 5 m/s、航向 090°、巢在船起点正西 10 km、
 * 机巡航 15 m/s、代表风况 0）记录会面点与起飞时刻，作为后续接入风况时的对比基准。
 *
 * <p>四个阈值与 `sys_param` 的会面键同口径：horizon 1800 s、窗口余量 120 s、收敛阈值 1 s、迭代 50。
 * 手算理想值：τ* = (10 000 + 5t) / 15 ⇒ t = 1 000 s；会面点在船起点正东 5 000 m；
 * τ_coarse = 巢到会面点 15 km ÷ 15 m/s = 1 000 s；起飞时刻 = t* − τ_coarse ≈ NOW。
 * 生产收敛阈值 ε=1 s 的固定点结果可能早于真实根（现按二分根取值，实测 t*＝+1 000 000 ms、
 * 起飞＝0 ms）；断言按 ε 取容差，精确基线数值见运行输出 `WIND-ZERO BASELINE` 行。
 */
class RendezvousWindZeroBaselineTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final double LAT0 = 30.0;
    private static final double LON0 = 121.0;
    private static final double NEST_GAP_M = 10_000.0;
    private static final double CRUISE_SPEED_MS = 15.0;
    private static final long HORIZON_SEC = 1_800L;
    private static final int WINDOW_MARGIN_SEC = 120;
    private static final double CONVERGE_EPSILON_SEC = 1.0;
    private static final int MAX_ITERATIONS = 50;
    private static final String MMSI = "413000001";
    private static final String VERSION = "PV-WIND0";

    @Test
    void windZeroBaseline_shouldPinMeetPointAndTakeoffTime() {
        RendezvousSolveService service = new RendezvousSolveService(new FixedPort(snapshot()));

        B_RendezvousSolution res = service.solve(command());

        assertTrue(res.hasSolution(), "风＝0 固定输入应有解");
        assertTrue(Math.abs(res.meetTMin() - (NOW + 1_000_000L)) <= 1_000L,
                "会面时刻 t* 与手算 1000 s 之差在收敛阈值内（实际 " + (res.meetTMin() - NOW) + " ms）");
        assertEquals(120_000L, res.meetTMax() - res.meetTMin(), "会面窗上界 = t* + 120 s");
        assertEquals(1_000, res.tauCoarse().intValue(), "τ_coarse = 巢到会面点约 15 km ÷ 15 m/s");
        assertEquals(LAT0, res.meetLat().doubleValue(), 1e-9, "会面点纬度不变（正东航向）");
        assertEquals(LON0 + 5_000.0 / mPerDegLon(LAT0), res.meetLon().doubleValue(), 1e-4,
                "会面点在船起点正东约 5000 m");
        assertTrue(Math.abs((res.meetTMin() - res.tauCoarse() * 1000L) - NOW) <= 1_000L,
                "起飞时刻与 NOW 之差在收敛阈值内（实际 "
                        + (res.meetTMin() - res.tauCoarse() * 1000L - NOW) + " ms）");

        System.out.printf(
                "WIND-ZERO BASELINE meet=(%.9f, %.9f) meetT=+%dms window=[+%d,+%d]ms tau=%ds takeoff=%dms%n",
                res.meetLon(), res.meetLat(), res.meetTMin() - NOW, res.meetTMin() - NOW,
                res.meetTMax() - NOW, res.tauCoarse(), res.meetTMin() - res.tauCoarse() * 1000L - NOW);
    }

    private static RendezvousCommand command() {
        return new RendezvousCommand(
                "combo-wind0", MMSI, VERSION,
                nestLon(), LAT0, CRUISE_SPEED_MS, new Wind(0, 0, 0),
                NOW, NOW + HORIZON_SEC * 1000L, WINDOW_MARGIN_SEC, CONVERGE_EPSILON_SEC, MAX_ITERATIONS);
    }

    /** 船东行 5 m/s 的匀速直线船迹（400 点 × 10 s，覆盖 1800 s 时域） */
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
        return LON0 - NEST_GAP_M / mPerDegLon(LAT0);
    }

    private static double mPerDegLon(double lat) {
        return Math.PI * 6_371_000.0 / 180.0 * Math.cos(Math.toRadians(lat));
    }

    /** 固定供数的快照端口桩——与 RendezvousFacadeTest 同写法 */
    private static final class FixedPort implements TrajectorySnapshotPort {

        private final ShipTrackSnapshot snapshot;

        FixedPort(ShipTrackSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public ShipTrackSnapshot latest(String shipMmsi) {
            return snapshot;
        }
    }
}
