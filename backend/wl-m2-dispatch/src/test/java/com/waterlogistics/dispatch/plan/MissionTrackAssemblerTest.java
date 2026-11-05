package com.waterlogistics.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissionTrackAssemblerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final double NEST_LON = 115.848;
    private static final double NEST_LAT = 28.642;
    private static final double MEET_LON = 115.870;
    private static final double MEET_LAT = 28.680;
    private static final double HANDOVER_LON = 115.8701;
    private static final double HANDOVER_LAT = 28.6801;

    @Test
    void assemble_shouldChainThreeSegmentsFromMeetTime() {
        StubPort port = new StubPort();
        MissionTrackAssembler assembler = new MissionTrackAssembler(port, new SysParamLoader(null));
        MeetPlan plan = plan();

        PlanTrackFrame frame = assembler.assemble("ORD-1", "COMBO-1", plan);

        assertEquals("ORD-1", frame.orderId());
        assertEquals("RT-ORD-1", frame.routeId());
        assertEquals("COMBO-1", frame.comboId());
        assertEquals(PlanTrackFrame.OP_TYPE, frame.opType());
        assertEquals(3, frame.segments().size(), "固定三段：去程、末段交接、返程");
        assertEquals(PlanTrackFrame.SEGMENT_OUTBOUND, frame.segments().get(0).kind());
        assertEquals(PlanTrackFrame.SEGMENT_TERMINAL, frame.segments().get(1).kind());
        assertEquals(PlanTrackFrame.SEGMENT_RETURN, frame.segments().get(2).kind());

        assertEquals(3, port.commands.size(), "三段各调一次航迹生成");
        long meetMs = plan.meetTime().atZone(ZONE).toInstant().toEpochMilli();
        double outboundSec = GeoApprox.distanceM(NEST_LON, NEST_LAT, MEET_LON, MEET_LAT)
                / plan.droneSpeedMs();
        long expectedTakeoff = meetMs - Math.max(1L, Math.round(outboundSec)) * 1000L;
        assertEquals(expectedTakeoff, port.commands.get(0).startMs(), "起飞时刻按会面时刻减去去程时长反推");

        long outboundEnd = port.commands.get(0).startMs() + 60_000L;
        assertEquals(outboundEnd, port.commands.get(1).startMs(), "末段起点接去程末点");
        assertEquals(outboundEnd + 60_000L, port.commands.get(2).startMs(), "返程起点接末段末点");

        assertEquals(3, frame.segments().get(0).points().size(), "去程保留首点");
        assertEquals(2, frame.segments().get(1).points().size(), "后续段跳过重复的边界首点");
        assertEquals(2, frame.segments().get(2).points().size());
        assertNotNull(frame.segments().get(2).points().get(1).tMs());
    }

    private static MeetPlan plan() {
        return new MeetPlan(MEET_LON, MEET_LAT, LocalDateTime.of(2026, 11, 3, 12, 0),
                "SOL-1", "v1", NEST_LON, NEST_LAT, 12.0,
                HANDOVER_LON, HANDOVER_LAT, 4.12);
    }

    private static final class StubPort implements MissionTrackPort {

        private final List<GenerateRouteCommand> commands = new ArrayList<>();

        @Override
        public B_FlyableRoute generateTrack(GenerateRouteCommand cmd) {
            commands.add(cmd);
            long t0 = cmd.startMs();
            List<Waypoint> points = List.of(
                    new Waypoint(cmd.startLon(), cmd.startLat(), cmd.cruiseAltM(), cmd.speedMs(), t0),
                    new Waypoint((cmd.startLon() + cmd.endLon()) / 2.0,
                            (cmd.startLat() + cmd.endLat()) / 2.0,
                            cmd.cruiseAltM(), cmd.speedMs(), t0 + 30_000L),
                    new Waypoint(cmd.endLon(), cmd.endLat(), cmd.cruiseAltM(), cmd.speedMs(), t0 + 60_000L));
            return new B_FlyableRoute(cmd.routeId(), "v-" + cmd.routeId(), cmd.comboId(),
                    points, 60, 1.0, 0.0, List.of(), List.of());
        }
    }
}
