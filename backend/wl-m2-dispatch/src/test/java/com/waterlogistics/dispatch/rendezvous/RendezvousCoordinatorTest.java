package com.waterlogistics.dispatch.rendezvous;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.NoSolutionReason;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.dispatch.weather.WeatherSnapshotAdapter;
import com.waterlogistics.dispatch.weather.WeatherSnapshotPort;
import com.waterlogistics.dispatch.weather.WeatherSettings;
import com.waterlogistics.dispatch.weather.WeatherSnapshotStore;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 动态会面接线守卫（2026-10-17 新增）——M2 唯一调 M3 门面的那一步。
 *
 * <p>覆盖三种不得静默放行的情形：台账查无、快照缺失、求解无解。
 * 前两者是数据未就绪，第三者是追不上。三者都必须变成硬失败，
 * 不得退回当前时间加固定缓冲那种假会面时刻（接入前的演示占位）。
 *
 * <p>桩件一律继承或用 lambda，不引入 mock 框架（本项目单测不用）。
 */
class RendezvousCoordinatorTest {

    private static final String MMSI = "123456789";

    private static final long MEET_T_MS = 1_700_000_000_000L;

    private static final double LON0 = 121.0;

    private static final double LAT0 = 30.0;

    private static final double GAP_M = 10_000.0;

    private static OrderTaskEntity order() {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORDER-1");
        order.setShipMmsi(MMSI);
        return order;
    }

    private static DroneContext droneContext() {
        return new DroneContext("DRONE-1", "NEST-A", 120.10, 30.20, 12.0, null);
    }

    /** 有解时的正常路径：会面点、起飞时刻、解标识、快照版本都要回传给调度链 */
    @Test
    void solveFor_shouldReturnMeetPlanWhenSolvable() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(true), id -> droneContext(), mmsi -> snapshot(), sysParams(), (lon, lat, now) -> null);

        RendezvousCoordinator.MeetPlan plan = coordinator.solveFor(order(), "COMBO-1", "DRONE-1");

        assertNotNull(plan, "有解时必须给出方案");
        assertEquals(115.0, plan.meetLon(), 1e-9, "会面点经度取算法层结果");
        assertEquals(29.0, plan.meetLat(), 1e-9, "会面点纬度取算法层结果");
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(MEET_T_MS),
                ZoneId.of("Asia/Shanghai")), plan.takeoffTime(),
                "起飞时刻取会面时刻最早上界、且已转本地时区");
        assertEquals("SOL-1", plan.solutionId(), "解标识须回传便于留痕");
        assertEquals("1", plan.snapshotVersion(), "快照版本须与 M2 常驻层一致");
    }

    /** 台账查无：连求解起点都无从确定，必须在起步阶段就拒绝 */
    @Test
    void solveFor_shouldRejectWhenDroneLedgerMissing() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(true), id -> null, mmsi -> snapshot(), sysParams(), (lon, lat, now) -> null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-X"));

        assertTrue(thrown.getMessage().contains("台账或归属机巢查无"),
                "须点明是台账缺失：" + thrown.getMessage());
    }

    /** 快照缺失：不得用船位反推航迹（《29》3.5.2 纪律），必须拒绝 */
    @Test
    void solveFor_shouldRejectWhenSnapshotMissing() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(true), id -> droneContext(), mmsi -> null, sysParams(), (lon, lat, now) -> null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-1"));

        assertTrue(thrown.getMessage().contains("无预测快照"),
                "须点明是快照缺失：" + thrown.getMessage());
    }

    /** 求解无解（追不上）：接入动态会面后最核心的一条，不许假派发 */
    @Test
    void solveFor_shouldRejectWhenUnsolvable() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(false), id -> droneContext(), mmsi -> snapshot(), sysParams(), (lon, lat, now) -> null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-1"));

        assertTrue(thrown.getMessage().contains("会面求解无解"),
                "须点明是求解无解而非其它失败：" + thrown.getMessage());
    }

    /** 气象快照可用时：代表风况取最新观测，不落 sys_param 的静态值。 */
    @Test
    void solveFor_shouldUseLatestWeatherSnapshotForWind() {
        StubbedFacade facade = new StubbedFacade(true);
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                facade, id -> droneContext(), mmsi -> snapshot(), sysParams(),
                (lon, lat, now) -> new WeatherSnapshotPort.LatestWind(4.5, 300.0, MEET_T_MS));

        coordinator.solveFor(order(), "COMBO-1", "DRONE-1");

        Wind wind = facade.lastCommand().representativeWind();
        assertEquals(4.5, wind.speedMs(), 1e-9, "代表风况风速须取气象快照");
        assertEquals(300.0, wind.dirDeg(), 1e-9, "代表风况风向须取气象快照");
    }

    /** 气象快照缺失时：回落 sys_param 静态风况，主链不得中断。 */
    @Test
    void solveFor_shouldFallBackToSysParamsWhenWeatherMissing() {
        StubbedFacade facade = new StubbedFacade(true);
        SysParamLoader params = new SysParamLoader(null) {
            @Override
            public int getInt(String key, int defaultValue) {
                return defaultValue;
            }

            @Override
            public double getDouble(String key, double defaultValue) {
                return switch (key) {
                    case "windSpeedMs" -> 6.0;
                    case "windDirDeg" -> 120.0;
                    default -> defaultValue;
                };
            }
        };
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                facade, id -> droneContext(), mmsi -> snapshot(), params, (lon, lat, now) -> null);

        coordinator.solveFor(order(), "COMBO-1", "DRONE-1");

        Wind wind = facade.lastCommand().representativeWind();
        assertEquals(6.0, wind.speedMs(), 1e-9, "无气象快照时回落 sys_param 风速");
        assertEquals(120.0, wind.dirDeg(), 1e-9, "无气象快照时回落 sys_param 风向");
    }

    /** 风况随快照变化：顺风比逆风更早会面（真实求解器贯通）。 */
    @Test
    void solveFor_shouldShiftPlanWithWindFromSnapshot() {
        RendezvousCoordinator headwind = new RendezvousCoordinator(
                new RendezvousFacade(mmsi -> movingSnapshot(), id -> null),
                id -> droneContextAt(15.0), mmsi -> movingSnapshot(), sysParams(),
                (lon, lat, now) -> new WeatherSnapshotPort.LatestWind(3.0, 90.0, MEET_T_MS));
        RendezvousCoordinator tailwind = new RendezvousCoordinator(
                new RendezvousFacade(mmsi -> movingSnapshot(), id -> null),
                id -> droneContextAt(15.0), mmsi -> movingSnapshot(), sysParams(),
                (lon, lat, now) -> new WeatherSnapshotPort.LatestWind(3.0, 270.0, MEET_T_MS));

        RendezvousCoordinator.MeetPlan head = headwind.solveFor(order(), "COMBO-1", "DRONE-1");
        RendezvousCoordinator.MeetPlan tail = tailwind.solveFor(order(), "COMBO-1", "DRONE-1");

        assertTrue(tail.takeoffTime().isBefore(head.takeoffTime()),
                "顺风应更早会面：逆风 " + head.takeoffTime() + "，顺风 " + tail.takeoffTime());
    }

    /** 气象快照仓到适配器再到协调器：顺风应比逆风更早会面。 */
    @Test
    void solveFor_shouldConsumeWindFromStore() {
        long now = System.currentTimeMillis();
        RendezvousCoordinator headwind = new RendezvousCoordinator(
                new RendezvousFacade(mmsi -> movingSnapshot(), id -> null),
                id -> droneContextAt(15.0), mmsi -> movingSnapshot(), sysParams(),
                new WeatherSnapshotAdapter(storeWithWind(now, 90.0)));
        RendezvousCoordinator tailwind = new RendezvousCoordinator(
                new RendezvousFacade(mmsi -> movingSnapshot(), id -> null),
                id -> droneContextAt(15.0), mmsi -> movingSnapshot(), sysParams(),
                new WeatherSnapshotAdapter(storeWithWind(now, 270.0)));

        RendezvousCoordinator.MeetPlan head = headwind.solveFor(order(), "COMBO-1", "DRONE-1");
        RendezvousCoordinator.MeetPlan tail = tailwind.solveFor(order(), "COMBO-1", "DRONE-1");

        assertTrue(tail.takeoffTime().isBefore(head.takeoffTime()),
                "顺风应更早会面：逆风 " + head.takeoffTime() + "，顺风 " + tail.takeoffTime());
    }

    private static WeatherSnapshotStore storeWithWind(long obsMs, double dirDeg) {
        WeatherSnapshotStore store = new WeatherSnapshotStore(WeatherSettings.defaults());
        store.accept(new WeatherData(LON0 - GAP_M / mPerDegLon(LAT0), LAT0,
                3.0, dirDeg, 0.0, obsMs, obsMs, obsMs));
        return store;
    }

    /** 求解阈值桩：全部回缺省，代表库里没登记新键的情形 */
    private static SysParamLoader sysParams() {
        return new SysParamLoader(null) {
            @Override
            public int getInt(String key, int defaultValue) {
                return defaultValue;
            }

            @Override
            public double getDouble(String key, double defaultValue) {
                return defaultValue;
            }
        };
    }

    private static ShipTrackSnapshot snapshot() {
        return new ShipTrackSnapshot("1", MMSI, MEET_T_MS, 600,
                List.of(new ShipPoint(MEET_T_MS, 115.0, 29.0, 90.0, 10.0, 10.0)));
    }

    private static DroneContext droneContextAt(double cruiseMs) {
        return new DroneContext("DRONE-1", "NEST-A",
                LON0 - GAP_M / mPerDegLon(LAT0), LAT0, cruiseMs, null);
    }

    /** 匀速直线船迹：自当前时刻起东行 5 m/s，覆盖约 1990 s（求解时限内）。 */
    private static ShipTrackSnapshot movingSnapshot() {
        long now = System.currentTimeMillis() - 1_000L;
        double mpl = mPerDegLon(LAT0);
        List<ShipPoint> points = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            double dist = 5.0 * i * 10;
            points.add(new ShipPoint(now + (long) i * 10_000L, LON0 + dist / mpl, LAT0, 90.0, 0.0, 0.0));
        }
        return new ShipTrackSnapshot("1", MMSI, now, 86_400, points);
    }

    private static double mPerDeg() {
        return Math.PI * 6_371_000.0 / 180.0;
    }

    private static double mPerDegLon(double lat) {
        return mPerDeg() * Math.cos(Math.toRadians(lat));
    }

    /** 门面板：按 solvable 返回有解／无解（M3 是普通类，故用继承覆盖，不引入 mock 框架） */
    private static final class StubbedFacade extends RendezvousFacade {

        private final boolean solvable;

        private RendezvousCommand lastCommand;

        private StubbedFacade(boolean solvable) {
            // 门面构造器会对两个端口做 requireNonNull，故此处给占位实现；
            // 本桩已覆盖 solve()，这两个端口在测试中不会被调用。
            super(mmsi -> null, id -> null);
            this.solvable = solvable;
        }

        @Override
        public B_RendezvousSolution solve(RendezvousCommand cmd) {
            lastCommand = cmd;
            if (!solvable) {
                return B_RendezvousSolution.unsolvable("SOL-1", cmd.comboId(),
                        NoSolutionReason.CANNOT_CATCH, cmd.snapshotVersion());
            }
            return B_RendezvousSolution.solved("SOL-1", cmd.comboId(), 115.0, 29.0,
                    MEET_T_MS, MEET_T_MS, MEET_T_MS, 300, cmd.snapshotVersion());
        }

        RendezvousCommand lastCommand() {
            return lastCommand;
        }
    }
}