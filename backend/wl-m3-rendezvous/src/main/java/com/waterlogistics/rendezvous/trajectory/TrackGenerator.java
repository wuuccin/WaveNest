package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 航迹生成——「给定起点与终点，生成一条满足水域约束的可飞航迹」的<b>首版简化实现</b>
 * （《33-M3-路径规划》§3.1、《81-B组算法方案》§3.2）。
 *
 * <p><b>简化口径（《43-编码分工说明》§1；答辩口径「已规划、实现顺延」）</b>：首版按
 * 直线插值铺点，不做栅格 A* 寻路、不做视线拉直与圆弧平滑、不做高度剖面与动态绕行——
 * 精算版在 {@code generate} 内部替换寻路内核即可，调用侧接口不变。
 *
 * <p>「一项能力，两次调用」（《33》§4.1）：去程与返程走同一实现，只换起点、终点与
 * 风况取值；{@link #generateMissionTrack} 把三段（去程／末段交接／返程）拼成一份带
 * 分段标签的演示航迹，供大屏分段着色。
 *
 * <p>已知局限（首版注明）：时长按大圆距离计算、航点按经纬度<b>线性内插</b>——短航段
 * 两者差异可忽略；跨 180° 经线的航段会绕行（演示不覆盖，精算版随寻路内核一并处理）。
 */
public final class TrackGenerator {

    private TrackGenerator() {
    }

    /**
     * 生成一段可飞航迹（起点 → 终点）。
     *
     * @param cmd 生成入参（航点数与速度等前置由 {@link GenerateRouteCommand} 构造期校验）
     * @return 可飞航迹；{@code routeVersion} 由 routeId 与起点时刻确定性生成，
     *         重算（6a 会面点重算、D 功能 7 定案后重出）换起点时刻即换新版本
     */
    public static B_FlyableRoute generate(GenerateRouteCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");

        double distanceM = GeoApprox.distanceM(
                cmd.startLon(), cmd.startLat(), cmd.endLon(), cmd.endLat());
        int tauFine = Math.max(1, (int) Math.round(distanceM / cmd.speedMs()));

        List<Waypoint> waypoints = new ArrayList<>(cmd.waypointCount());
        for (int i = 0; i < cmd.waypointCount(); i++) {
            double k = (double) i / (cmd.waypointCount() - 1);
            waypoints.add(new Waypoint(
                    cmd.startLon() + k * (cmd.endLon() - cmd.startLon()),
                    cmd.startLat() + k * (cmd.endLat() - cmd.startLat()),
                    cmd.cruiseAltM(),
                    cmd.speedMs(),
                    cmd.startMs() + Math.round(k * tauFine * 1000.0)));
        }

        return new B_FlyableRoute(
                cmd.routeId(),
                "RV-" + cmd.routeId() + "-" + cmd.startMs(),
                cmd.comboId(),
                waypoints,
                tauFine,
                tauFine * cmd.energyRatePerSecPct(),
                cmd.pathRisk(),
                cmd.landingPointsAlong(),
                cmd.avoidZones());
    }

    /**
     * 生成三段任务航迹（去程／末段交接／返程）并打分段标签——供大屏分段着色
     * （《43》§6.3）。三段分别走 {@link #generate} 同一项能力，时间自起飞时刻顺延。
     *
     * <p>返回的分段航迹<b>只含航点与分段标签</b>：能耗／风险／迫降点／避让区等元数据
     * 不随本结构输出（分段着色的需要仅为点序、时刻与标签）。
     *
     * @param cmd 三段几何与参数
     * @return 分段任务航迹（去程 → 末段交接 → 返程 顺序）
     */
    public static SegmentedTrack generateMissionTrack(MissionTrackCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");
        B_FlyableRoute outbound = generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-out",
                cmd.nestLon(), cmd.nestLat(), cmd.meetLon(), cmd.meetLat(),
                cmd.cruiseAltM(), cmd.outboundSpeedMs(), cmd.takeoffMs(),
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        long terminalStartMs = outbound.waypoints().get(outbound.waypoints().size() - 1).t();
        B_FlyableRoute terminal = generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-term",
                cmd.meetLon(), cmd.meetLat(), cmd.handoverEndLon(), cmd.handoverEndLat(),
                cmd.cruiseAltM(), cmd.terminalSpeedMs(), terminalStartMs,
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        long returnStartMs = terminal.waypoints().get(terminal.waypoints().size() - 1).t();
        B_FlyableRoute returning = generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-ret",
                cmd.handoverEndLon(), cmd.handoverEndLat(), cmd.returnTargetLon(), cmd.returnTargetLat(),
                cmd.cruiseAltM(), cmd.returnSpeedMs(), returnStartMs,
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        List<SegmentedWaypoint> points = new ArrayList<>(
                cmd.waypointsPerLeg() * 3);
        outbound.waypoints().forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.OUTBOUND)));
        terminal.waypoints().forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.TERMINAL)));
        returning.waypoints().forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.RETURN)));

        return new SegmentedTrack(points);
    }
}
