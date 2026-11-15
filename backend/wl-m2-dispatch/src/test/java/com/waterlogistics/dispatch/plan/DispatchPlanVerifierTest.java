package com.waterlogistics.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.algo.meeting.model.ConflictSpaceTime;
import com.waterlogistics.algo.meeting.model.CpaPoint;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.algo.meeting.model.TakeoffInfeasibleReason;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.dispatch.rendezvous.RendezvousVerificationPort;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import com.waterlogistics.storage.repository.SysParamMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/** 三个门面方法的固定顺序与「错峰 → 绕行 → 回退换机」三级冲突处置。 */
class DispatchPlanVerifierTest {

    private static final long T0 = 1_790_000_000_000L;
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void verify_shouldCallThreeFacadeMethodsInFixedOrder() {
        StubPort port = new StubPort();
        DispatchPlanVerifier verifier = verifier(port);

        DispatchPlanVerifier.Outcome outcome = verifier.verify(frame(), plan(), "ORD-1",
                "CB-1", "UAV-1", "123456789", T0);

        assertEquals(List.of("takeoff", "conflict", "handover"), port.calls);
        assertEquals(1, outcome.conflictChecks());
        assertEquals(0L, outcome.shiftMs());
        assertEquals(T0 + 598_000L, outcome.takeoffMs());
        assertFalse(outcome.handoverPredicted(), "计划预检不放 dropDone，不得写成交接成功");
    }

    @Test
    void conflict_shouldStaggerAndReCheck() {
        StubPort port = new StubPort();
        port.conflicts.add(conflictResult());
        DispatchPlanVerifier verifier = verifier(port);

        DispatchPlanVerifier.Outcome outcome = verifier.verify(frame(), plan(), "ORD-1",
                "CB-1", "UAV-1", "123456789", T0);

        assertEquals(3_000L, outcome.shiftMs(), "错峰＝三个采样步长（1 s × 3）");
        assertEquals(2, outcome.conflictChecks());
        assertEquals(List.of("takeoff", "conflict", "conflict", "handover"), port.calls);
        assertEquals(T0 + 603_000L, port.lastPlannedMeetT, "末段预检须按错峰后的会面时刻");
    }

    @Test
    void conflict_shouldFallbackToOtherCandidateWhenStaggerAndDetourFail() {
        StubPort port = new StubPort();
        port.conflicts.add(conflictResult());
        port.conflicts.add(conflictResult());
        DispatchPlanVerifier verifier = verifier(port);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> verifier.verify(frame(), plan(), "ORD-1", "CB-1", "UAV-1", "123456789", T0));
        assertTrue(thrown.getMessage().contains("回退换候选组合"), thrown.getMessage());
        assertEquals(List.of("takeoff", "conflict", "conflict"), port.calls,
                "未消除冲突不得放行，也不得继续做交接预检");
    }

    @Test
    void infeasibleTakeoff_shouldOnlyLogNotAbort() {
        StubPort port = new StubPort();
        port.takeoffFeasible = false;
        DispatchPlanVerifier verifier = verifier(port);

        DispatchPlanVerifier.Outcome outcome = verifier.verify(frame(), plan(), "ORD-1",
                "CB-1", "UAV-1", "123456789", T0);

        assertEquals(List.of("takeoff", "conflict", "handover"), port.calls,
                "起飞时刻门面只算不定案，不可行不得阻断后续定案校验");
        assertEquals(0L, outcome.shiftMs());
    }

    private static DispatchPlanVerifier verifier(StubPort port) {
        return new DispatchPlanVerifier(port, sysParamLoader(), orderTaskMapper(),
                planTrackMapper(), mmsi -> null);
    }

    private static PlanTrackFrame frame() {
        return new PlanTrackFrame("ORD-1", "RT-1", "CB-1", PlanTrackFrame.OP_TYPE,
                List.of(
                        new PlanTrackFrame.Segment(PlanTrackFrame.SEGMENT_OUTBOUND,
                                List.of(point(115.86, T0), point(115.861, T0 + 1_000L),
                                        point(115.862, T0 + 2_000L))),
                        new PlanTrackFrame.Segment(PlanTrackFrame.SEGMENT_TERMINAL,
                                List.of(point(115.863, T0 + 3_000L),
                                        point(115.864, T0 + 4_000L),
                                        point(115.865, T0 + 5_000L))),
                        new PlanTrackFrame.Segment(PlanTrackFrame.SEGMENT_RETURN,
                                List.of(point(115.866, T0 + 6_000L),
                                        point(115.867, T0 + 7_000L),
                                        point(115.868, T0 + 8_000L)))));
    }

    private static PlanTrackFrame.Point point(double lon, long tMs) {
        return new PlanTrackFrame.Point(lon, 28.66, 60.0, 12.0, tMs);
    }

    private static MeetPlan plan() {
        LocalDateTime meetTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(T0 + 600_000L), DB_ZONE);
        return new MeetPlan(115.862, 28.66, meetTime, "SOL-1", "1",
                115.85, 28.65, 12.0, 115.865, 28.66, 4.0, 115.85, 28.65);
    }

    private static B_ConflictResult conflictResult() {
        return new B_ConflictResult(true,
                new ConflictSpaceTime(null, T0, T0 + 8_000L),
                new CpaPoint(115.86, 28.66, 60.0), T0 + 1_000L, 10.0,
                null, "RT-1");
    }

    private static SysParamLoader sysParamLoader() {
        SysParamMapper mapper = (SysParamMapper) Proxy.newProxyInstance(
                SysParamMapper.class.getClassLoader(), new Class<?>[]{SysParamMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? List.of() : null);
        SysParamLoader loader = new SysParamLoader(mapper);
        loader.refresh();
        return loader;
    }

    private static OrderTaskMapper orderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName()) ? List.of() : null);
    }

    private static PlanTrackMapper planTrackMapper() {
        return (PlanTrackMapper) Proxy.newProxyInstance(
                PlanTrackMapper.class.getClassLoader(), new Class<?>[]{PlanTrackMapper.class},
                (proxy, method, args) -> "selectByOrderId".equals(method.getName()) ? List.of() : null);
    }

    private static final class StubPort implements RendezvousVerificationPort {
        private final List<String> calls = new ArrayList<>();
        private final Queue<B_ConflictResult> conflicts = new ArrayDeque<>();
        private boolean takeoffFeasible = true;
        private long lastPlannedMeetT;

        @Override
        public B_TakeoffTimeResult computeTakeoffTime(TakeoffTimeCommand cmd) {
            calls.add("takeoff");
            return new B_TakeoffTimeResult(cmd.comboId(), cmd.meetMs() - 2_000L,
                    takeoffFeasible,
                    takeoffFeasible ? null : TakeoffInfeasibleReason.BEFORE_NOW,
                    cmd.tauFineSec());
        }

        @Override
        public B_ConflictResult detectConflict(B_FlyableRoute route, B_FlyableRoute other,
                ConflictParams params) {
            calls.add("conflict");
            return conflicts.isEmpty()
                    ? new B_ConflictResult(false, null, null, null, null, null, route.routeVersion())
                    : conflicts.poll();
        }

        @Override
        public B_HandoverConfirmation judgeHandover(HandoverCommand cmd) {
            calls.add("handover");
            lastPlannedMeetT = cmd.plannedMeetT();
            return new B_HandoverConfirmation(cmd.taskId(), cmd.droneId(), null, null,
                    cmd.plannedMeetLon(), cmd.plannedMeetLat(), cmd.plannedMeetT(),
                    null, null, null, null, HandoverResult.FAILED);
        }
    }
}
