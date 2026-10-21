package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * 两者差异可忽略；跨 ±180° 经线的航段按短侧归一化铺点（与距离口径一致）。
 */
public final class TrackGenerator {

    /** 航迹生成内核版本：生成规则变化时必须递增，否则同输入的 routeVersion 不会更新 */
    private static final String ALGORITHM_VERSION = "track-gen-v1";

    private TrackGenerator() {
    }





    /**
     * 经度回卷到 [-180, 180)，保证跨 ±180° 铺点仍落在合法范围
     */
    private static double wrapLon(double lon) {
        double wrapped = (lon + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }





    /**
     * 直线插值铺点——经差同距离口径归一化（跨 ±180° 走短侧），经度回卷到 [-180, 180)。
     *
     * @param cmd     生成入参（航点数、起终点与速度已由 {@link GenerateRouteCommand} 构造期校验）
     * @param tauFine 精算飞行时长（秒，至少 1），航点时刻按此比例铺点
     * @return 均匀铺点后的航点序列（首点为起点、末点为终点）
     */
    @SuppressWarnings("ObjectAllocationInLoop")
    private static List<Waypoint> buildWaypoints(GenerateRouteCommand cmd, int tauFine) {
        double dLon = cmd.endLon() - cmd.startLon();
        if (dLon > 180.0) {
            dLon -= 360.0;
        } else if (dLon < -180.0) {
            dLon += 360.0;
        }
        List<Waypoint> waypoints = new ArrayList<>(cmd.waypointCount());
        int waypointCount = cmd.waypointCount();
        for (int i = 0; i < waypointCount; i++) {
            double frac = (double) i / (double) (waypointCount - 1);
            waypoints.add(new Waypoint(
                    TrackGenerator.wrapLon(cmd.startLon() + frac * dLon),
                    cmd.startLat() + frac * (cmd.endLat() - cmd.startLat()),
                    cmd.cruiseAltM(),
                    cmd.speedMs(),
                    cmd.startMs() + Math.round(frac * (double) tauFine * 1000.0)));
        }
        return waypoints;
    }





    /**
     * 生成一段可飞航迹（起点 → 终点）。
     *
     * @param cmd 生成入参（航点数与速度等前置由 {@link GenerateRouteCommand} 构造期校验）
     * @return 可飞航迹；{@code routeVersion} 是 routeId、起点时刻与几何内容的内容指纹，
     * 同一输入重出得到同一版本，任一有效输入变化即换新版本
     */
    public static B_FlyableRoute generate(GenerateRouteCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");

        double distanceM = GeoApprox.distanceM(cmd.startLon(), cmd.startLat(), cmd.endLon(), cmd.endLat());
        double tauSec = distanceM / cmd.speedMs();
        long tauRounded = Math.round(tauSec);
        if (tauRounded > (long) Integer.MAX_VALUE) {
            throw new IllegalArgumentException("航段精算时长超出可表示范围（" + tauSec + " s），请检查速度与几何输入");
        }
        int tauFine = (int) Math.max(1L, tauRounded);

        List<Waypoint> waypoints = TrackGenerator.buildWaypoints(cmd, tauFine);

        return new B_FlyableRoute(
                cmd.routeId(),
                "RV-" + cmd.routeId() + "-" + cmd.startMs() + "-" + TrackGenerator.fingerprint(cmd),
                cmd.comboId(),
                waypoints,
                tauFine,
                Math.min(100.0, (double) tauFine * cmd.energyRatePerSecPct()),
                cmd.pathRisk(),
                cmd.landingPointsAlong(),
                cmd.avoidZones()
        );
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
        B_FlyableRoute outbound = TrackGenerator.generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-out",
                cmd.nestLon(), cmd.nestLat(), cmd.meetLon(), cmd.meetLat(),
                cmd.cruiseAltM(), cmd.outboundSpeedMs(), cmd.takeoffMs(),
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        long terminalStartMs = outbound.waypoints().get(outbound.waypoints().size() - 1).t();
        B_FlyableRoute terminal = TrackGenerator.generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-term",
                cmd.meetLon(), cmd.meetLat(), cmd.handoverEndLon(), cmd.handoverEndLat(),
                cmd.cruiseAltM(), cmd.terminalSpeedMs(), terminalStartMs,
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        long returnStartMs = terminal.waypoints().get(terminal.waypoints().size() - 1).t();
        B_FlyableRoute returning = TrackGenerator.generate(new GenerateRouteCommand(
                cmd.comboId(), cmd.routeId() + "-ret",
                cmd.handoverEndLon(), cmd.handoverEndLat(), cmd.returnTargetLon(), cmd.returnTargetLat(),
                cmd.cruiseAltM(), cmd.returnSpeedMs(), returnStartMs,
                cmd.waypointsPerLeg(), List.of(), List.of(), 0.0, 0.0));

        // 段边界点只保留一次：拼接前先确认相邻两段的边界点确实一致，防未来改动静默丢点
        Waypoint outboundEnd = outbound.waypoints().get(outbound.waypoints().size() - 1);
        Waypoint terminalStart = terminal.waypoints().get(0);
        if (!sameBoundary(outboundEnd, terminalStart)) {
            throw new IllegalStateException("段边界航点不一致：末段首点应等于去程末点");
        }
        Waypoint terminalEnd = terminal.waypoints().get(terminal.waypoints().size() - 1);
        Waypoint returnStart = returning.waypoints().get(0);
        if (!sameBoundary(terminalEnd, returnStart)) {
            throw new IllegalStateException("段边界航点不一致：返程首点应等于末段末点");
        }
        List<SegmentedWaypoint> points = new ArrayList<>(cmd.waypointsPerLeg() * 3 - 2);
        outbound.waypoints().forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.OUTBOUND)));
        terminal.waypoints().subList(1, terminal.waypoints().size())
                .forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.TERMINAL)));
        returning.waypoints().subList(1, returning.waypoints().size())
                .forEach(w -> points.add(new SegmentedWaypoint(w, TrackSegment.RETURN)));

        return new SegmentedTrack(points);
    }





    /**
     * 生效输入指纹（SHA-256 前 16 位十六进制），保证任一输入变化即换版本
     */
    private static String fingerprint(GenerateRouteCommand cmd) {
        StringBuilder payload = new StringBuilder(256);
        TrackGenerator.appendField(payload, TrackGenerator.ALGORITHM_VERSION);
        TrackGenerator.appendField(payload, cmd.routeId());
        TrackGenerator.appendField(payload, cmd.comboId());
        TrackGenerator.appendField(payload, Long.toString(cmd.startMs()));
        TrackGenerator.appendField(payload, Double.toString(cmd.startLon()));
        TrackGenerator.appendField(payload, Double.toString(cmd.startLat()));
        TrackGenerator.appendField(payload, Double.toString(cmd.endLon()));
        TrackGenerator.appendField(payload, Double.toString(cmd.endLat()));
        TrackGenerator.appendField(payload, Double.toString(cmd.cruiseAltM()));
        TrackGenerator.appendField(payload, Double.toString(cmd.speedMs()));
        TrackGenerator.appendField(payload, Integer.toString(cmd.waypointCount()));
        TrackGenerator.appendField(payload, Double.toString(cmd.energyRatePerSecPct()));
        TrackGenerator.appendField(payload, Double.toString(cmd.pathRisk()));
        TrackGenerator.appendField(payload, cmd.landingPointsAlong().toString());
        TrackGenerator.appendField(payload, cmd.avoidZones().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", e);
        }
    }





    private static boolean sameBoundary(Waypoint left, Waypoint right) {
        return left.t() == right.t()
                && Double.compare(left.lon(), right.lon()) == 0
                && Double.compare(left.lat(), right.lat()) == 0
                && Double.compare(left.altM(), right.altM()) == 0;
    }

    /**
     * 长度前缀写入，避免分隔符或列表文本串位导致指纹碰撞
     */
    private static void appendField(StringBuilder payload, String value) {
        payload.append(value.length()).append(':').append(value).append(';');
    }
}
