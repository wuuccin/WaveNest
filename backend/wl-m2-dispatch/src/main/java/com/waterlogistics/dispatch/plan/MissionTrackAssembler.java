package com.waterlogistics.dispatch.plan;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 三段任务航迹装配器：把会面方案装配成去程、末段交接、返程三段可飞航迹，
 * 再投影成规划航迹帧。
 *
 * <p>门面纪律：M2 只经 {@link MissionTrackPort} 生成航迹，不直接引用 M3 的航迹生成器。
 * 起飞时刻按会面时刻减去去程时长反推；后续两段的起点时刻取上一段末点的到达时刻。
 *
 * <p><b>货巢与机巢不同时</b>：先补一段空机转场（无人机当前所在巢 → 货物所在巢），
 * 再生成取货后去程（货物所在巢 → 会面点）。转场使用独立分段词，去程仍用去程口径，
 * 起飞时刻按转场时长加去程时长一起反推。返程仍回无人机当前所在巢。
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

    /** 转场判定容差：两巢坐标差不足一米时不出转场段，避免退化航段。 */
    private static final double TRANSFER_MIN_DISTANCE_M = 1.0;

    private final MissionTrackPort trackPort;
    private final SysParamLoader sysParamLoader;

    public MissionTrackAssembler(MissionTrackPort trackPort, SysParamLoader sysParamLoader) {
        this.trackPort = trackPort;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 装配规划航迹并投影成规划航迹帧。
     *
     * @param orderId 订单号
     * @param comboId 候选组合标识
     * @param plan    会面方案，含求解起点、无人机当前巢、会面点、交接完成位置与三段速度
     * @return 规划航迹帧
     */
    public PlanTrackFrame assemble(String orderId, String comboId, MeetPlan plan) {
        return assemble(orderId, comboId, plan, "");
    }

    /**
     * 装配规划航迹并投影成规划航迹帧（带航迹版本）。
     *
     * <p>《1112-接口约定》§2.2「版本保留」：重规划产生新 {@code route_id}，
     * 旧 {@code route_id} 行保留供复盘；{@code uk_plan_point} 含 {@code route_id}，
     * 故重派（同一订单再走一次编排）必须换号，否则批量落点会撞唯一键。
     *
     * @param orderId      订单号
     * @param comboId      候选组合标识
     * @param plan         会面方案
     * @param routeVersion 航迹版本后缀；空串表示不带版本（兼容既有装配单测）
     * @return 规划航迹帧
     */
    public PlanTrackFrame assemble(String orderId, String comboId, MeetPlan plan,
            String routeVersion) {
        double cruiseAltM = sysParamLoader.getDouble(CRUISE_ALT_KEY, DEFAULT_CRUISE_ALT_M);
        int waypoints = clampWaypoints(
                sysParamLoader.getInt(WAYPOINTS_PER_LEG_KEY, DEFAULT_WAYPOINTS_PER_LEG));
        String routeId = "RT-" + orderId
                + (routeVersion == null || routeVersion.isBlank() ? "" : "-" + routeVersion);

        long meetMs = plan.meetTime().atZone(DB_ZONE).toInstant().toEpochMilli();
        double transferDistanceM = GeoApprox.distanceM(
                plan.droneNestLon(), plan.droneNestLat(), plan.nestLon(), plan.nestLat());
        boolean transfer = transferDistanceM >= TRANSFER_MIN_DISTANCE_M;
        double transferSec = transfer ? transferDistanceM / plan.droneSpeedMs() : 0.0;
        double outboundSec = GeoApprox.distanceM(
                plan.nestLon(), plan.nestLat(), plan.meetLon(), plan.meetLat()) / plan.droneSpeedMs();
        long transferRoundedSec = transfer ? Math.max(1L, Math.round(transferSec)) : 0L;
        long outboundRoundedSec = Math.max(1L, Math.round(outboundSec));
        long takeoffMs = meetMs - (transferRoundedSec + outboundRoundedSec) * 1000L;

        B_FlyableRoute transferRoute = null;
        long outboundStartMs = takeoffMs;
        if (transfer) {
            transferRoute = trackPort.generateTrack(new GenerateRouteCommand(
                    comboId, routeId + "-transfer",
                    plan.droneNestLon(), plan.droneNestLat(), plan.nestLon(), plan.nestLat(),
                    cruiseAltM, plan.droneSpeedMs(), takeoffMs, waypoints,
                    List.of(), List.of(), 0.0, 0.0));
            outboundStartMs = lastArrivalMs(transferRoute);
        }

        B_FlyableRoute outbound = trackPort.generateTrack(new GenerateRouteCommand(
                comboId, routeId + "-out",
                plan.nestLon(), plan.nestLat(), plan.meetLon(), plan.meetLat(),
                cruiseAltM, plan.droneSpeedMs(), outboundStartMs, waypoints,
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
                plan.handoverEndLon(), plan.handoverEndLat(),
                plan.droneNestLon(), plan.droneNestLat(),
                cruiseAltM, plan.droneSpeedMs(), returnStartMs, waypoints,
                List.of(), List.of(), 0.0, 0.0));

        List<PlanTrackFrame.Segment> segments = new ArrayList<>();
        if (transferRoute != null) {
            segments.add(new PlanTrackFrame.Segment(
                    PlanTrackFrame.SEGMENT_TRANSFER, pointsOf(transferRoute, false)));
        }
        segments.add(new PlanTrackFrame.Segment(
                PlanTrackFrame.SEGMENT_OUTBOUND, pointsOf(outbound, transfer)));
        segments.add(new PlanTrackFrame.Segment(
                PlanTrackFrame.SEGMENT_TERMINAL, pointsOf(terminal, true)));
        segments.add(new PlanTrackFrame.Segment(
                PlanTrackFrame.SEGMENT_RETURN, pointsOf(returning, true)));

        return new PlanTrackFrame(orderId, routeId, comboId, PlanTrackFrame.OP_TYPE, segments);
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
