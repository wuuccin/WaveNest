package com.waterlogistics.dispatch.plan;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 三段任务航迹装配器：把会面方案装配成去程、末段交接、返程三段可飞航迹，
 * 再投影成规划航迹帧。
 *
 * <p>门面纪律：M2 只经 {@link MissionTrackPort} 生成航迹，不直接引用 M3 的航迹生成器。
 * 起飞时刻按会面时刻减去去程时长反推；后续两段的起点时刻取上一段末点的到达时刻。
 */
@Component
public class MissionTrackAssembler {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CRUISE_ALT_KEY = "trackCruiseAltM";
    private static final String WAYPOINTS_PER_LEG_KEY = "trackWaypointsPerLeg";
    private static final double DEFAULT_CRUISE_ALT_M = 60.0;
    private static final int DEFAULT_WAYPOINTS_PER_LEG = 20;
    private static final int MIN_WAYPOINTS = 2;
    private static final int MAX_WAYPOINTS = 512;

    private final MissionTrackPort trackPort;
    private final SysParamLoader sysParamLoader;

    public MissionTrackAssembler(MissionTrackPort trackPort, SysParamLoader sysParamLoader) {
        this.trackPort = trackPort;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 装配三段航迹并投影成规划航迹帧。
     *
     * @param orderId 订单号
     * @param comboId 候选组合标识
     * @param plan    会面方案，含机巢、会面点、交接完成位置与三段速度
     * @return 规划航迹帧
     */
    public PlanTrackFrame assemble(String orderId, String comboId, MeetPlan plan) {
        double cruiseAltM = sysParamLoader.getDouble(CRUISE_ALT_KEY, DEFAULT_CRUISE_ALT_M);
        int waypoints = clampWaypoints(
                sysParamLoader.getInt(WAYPOINTS_PER_LEG_KEY, DEFAULT_WAYPOINTS_PER_LEG));
        String routeId = "RT-" + orderId;

        long meetMs = plan.meetTime().atZone(DB_ZONE).toInstant().toEpochMilli();
        double outboundSec = GeoApprox.distanceM(
                plan.nestLon(), plan.nestLat(), plan.meetLon(), plan.meetLat()) / plan.droneSpeedMs();
        long takeoffMs = meetMs - Math.max(1L, Math.round(outboundSec)) * 1000L;

        B_FlyableRoute outbound = trackPort.generateTrack(new GenerateRouteCommand(
                comboId, routeId + "-out",
                plan.nestLon(), plan.nestLat(), plan.meetLon(), plan.meetLat(),
                cruiseAltM, plan.droneSpeedMs(), takeoffMs, waypoints,
                List.of(), List.of(), 0.0, 0.0));

        long terminalStartMs = lastArrivalMs(outbound);
        B_FlyableRoute terminal = trackPort.generateTrack(new GenerateRouteCommand(
                comboId, routeId + "-term",
                plan.meetLon(), plan.meetLat(), plan.handoverEndLon(), plan.handoverEndLat(),
                cruiseAltM, plan.terminalSpeedMs(), terminalStartMs, waypoints,
                List.of(), List.of(), 0.0, 0.0));

        long returnStartMs = lastArrivalMs(terminal);
        B_FlyableRoute returning = trackPort.generateTrack(new GenerateRouteCommand(
                comboId, routeId + "-ret",
                plan.handoverEndLon(), plan.handoverEndLat(), plan.nestLon(), plan.nestLat(),
                cruiseAltM, plan.droneSpeedMs(), returnStartMs, waypoints,
                List.of(), List.of(), 0.0, 0.0));

        return new PlanTrackFrame(
                orderId,
                routeId,
                comboId,
                PlanTrackFrame.OP_TYPE,
                List.of(
                        new PlanTrackFrame.Segment(
                                PlanTrackFrame.SEGMENT_OUTBOUND, pointsOf(outbound, false)),
                        new PlanTrackFrame.Segment(
                                PlanTrackFrame.SEGMENT_TERMINAL, pointsOf(terminal, true)),
                        new PlanTrackFrame.Segment(
                                PlanTrackFrame.SEGMENT_RETURN, pointsOf(returning, true))));
    }

    private static long lastArrivalMs(B_FlyableRoute route) {
        List<Waypoint> waypoints = route.waypoints();
        return waypoints.get(waypoints.size() - 1).t();
    }

    private static List<PlanTrackFrame.Point> pointsOf(B_FlyableRoute route, boolean skipFirst) {
        List<Waypoint> waypoints = route.waypoints();
        int from = skipFirst && waypoints.size() > 1 ? 1 : 0;
        return waypoints.subList(from, waypoints.size()).stream()
                .map(p -> new PlanTrackFrame.Point(p.lon(), p.lat(), p.altM(), p.speedMs(), p.t()))
                .toList();
    }

    private static int clampWaypoints(int value) {
        return Math.max(MIN_WAYPOINTS, Math.min(MAX_WAYPOINTS, value));
    }
}
