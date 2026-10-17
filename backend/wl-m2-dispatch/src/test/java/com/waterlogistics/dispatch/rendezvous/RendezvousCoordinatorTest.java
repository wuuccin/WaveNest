package com.waterlogistics.dispatch.rendezvous;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.NoSolutionReason;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
                new StubbedFacade(true), id -> droneContext(), mmsi -> snapshot(), sysParams());

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
                new StubbedFacade(true), id -> null, mmsi -> snapshot(), sysParams());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-X"));

        assertTrue(thrown.getMessage().contains("台账或归属机巢查无"),
                "须点明是台账缺失：" + thrown.getMessage());
    }

    /** 快照缺失：不得用船位反推航迹（《29》3.5.2 纪律），必须拒绝 */
    @Test
    void solveFor_shouldRejectWhenSnapshotMissing() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(true), id -> droneContext(), mmsi -> null, sysParams());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-1"));

        assertTrue(thrown.getMessage().contains("无预测快照"),
                "须点明是快照缺失：" + thrown.getMessage());
    }

    /** 求解无解（追不上）：接入动态会面后最核心的一条，不许假派发 */
    @Test
    void solveFor_shouldRejectWhenUnsolvable() {
        RendezvousCoordinator coordinator = new RendezvousCoordinator(
                new StubbedFacade(false), id -> droneContext(), mmsi -> snapshot(), sysParams());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.solveFor(order(), "COMBO-1", "DRONE-1"));

        assertTrue(thrown.getMessage().contains("会面求解无解"),
                "须点明是求解无解而非其它失败：" + thrown.getMessage());
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

    /** 门面板：按 solvable 返回有解／无解（M3 是普通类，故用继承覆盖，不引入 mock 框架） */
    private static final class StubbedFacade extends RendezvousFacade {

        private final boolean solvable;

        private StubbedFacade(boolean solvable) {
            // 门面构造器会对两个端口做 requireNonNull，故此处给占位实现；
            // 本桩已覆盖 solve()，这两个端口在测试中不会被调用。
            super(mmsi -> null, id -> null);
            this.solvable = solvable;
        }

        @Override
        public B_RendezvousSolution solve(RendezvousCommand cmd) {
            if (!solvable) {
                return B_RendezvousSolution.unsolvable("SOL-1", cmd.comboId(),
                        NoSolutionReason.CANNOT_CATCH, cmd.snapshotVersion());
            }
            return B_RendezvousSolution.solved("SOL-1", cmd.comboId(), 115.0, 29.0,
                    MEET_T_MS, MEET_T_MS, MEET_T_MS, 300, cmd.snapshotVersion());
        }
    }
}