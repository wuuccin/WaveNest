package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.GeoApprox;
import java.util.Objects;



/**
 * 三段任务航迹生成入参——供大屏「无人机三段航迹」分段着色的演示数据
 * （《43-编码分工说明》§6.3：去程／末段交接／返程，产出分段着色数据）。
 *
 * <p>三段几何：去程「机巢 → 会面点」、末段交接「会面点 → 交接完成位置」、
 * 返程「交接完成位置 → 归巢落点」；三段分别走同一项航迹生成能力后拼接打标。
 * 时间自 {@code takeoffMs} 起按各段精算时长顺延。
 *
 * @param comboId         候选组合标识
 * @param routeId         任务航迹标识（三段航迹以其加后缀命名）
 * @param nestLon         机巢经度
 * @param nestLat         机巢纬度
 * @param meetLon         会面点经度
 * @param meetLat         会面点纬度
 * @param handoverEndLon  交接完成位置经度（末段终点）
 * @param handoverEndLat  交接完成位置纬度
 * @param returnTargetLon 归巢落点经度
 * @param returnTargetLat 归巢落点纬度
 * @param cruiseAltM      巡航高度，米
 * @param outboundSpeedMs 去程速度，m/s
 * @param terminalSpeedMs 末段速度，m/s（伴飞随船，通常显著低于巡航）
 * @param returnSpeedMs   返程速度，m/s
 * @param takeoffMs       起飞时刻（第一段起点时刻），Unix 毫秒
 * @param waypointsPerLeg 每段航点数（2–512）
 */
@SuppressWarnings("MissingJavadoc")
public record MissionTrackCommand(

        String comboId,

        String routeId,

        double nestLon,

        double nestLat,

        double meetLon,

        double meetLat,

        double handoverEndLon,

        double handoverEndLat,

        double returnTargetLon,

        double returnTargetLat,

        double cruiseAltM,

        double outboundSpeedMs,

        double terminalSpeedMs,

        double returnSpeedMs,

        long takeoffMs,

        int waypointsPerLeg) {


    /**
     * 合理纪元上界（2100-01-01）；起飞时刻与三段累计结束时刻都须落在界内
     */
    private static final long MAX_EPOCH_MS = 4_102_444_800_000L;

    /**
     * 各段速度下限（m/s），防极低速导致航段时长不可控
     */
    private static final double MIN_SPEED_MS = 0.1;

    private static final double MAX_SPEED_MS = 100.0;





    public MissionTrackCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        Objects.requireNonNull(routeId, "routeId 不能为空");
        if (comboId.isBlank() || routeId.isBlank()) {
            throw new IllegalArgumentException("comboId/routeId 不能为空白字符串");
        }
        if (!Double.isFinite(nestLon) || !Double.isFinite(nestLat)
                || !Double.isFinite(meetLon) || !Double.isFinite(meetLat)
                || !Double.isFinite(handoverEndLon) || !Double.isFinite(handoverEndLat)
                || !Double.isFinite(returnTargetLon) || !Double.isFinite(returnTargetLat)) {
            throw new IllegalArgumentException("三段几何坐标必须为有限数");
        }
        if (nestLon < -180.0 || nestLon > 180.0 || nestLat < -90.0 || nestLat > 90.0
                || meetLon < -180.0 || meetLon > 180.0 || meetLat < -90.0 || meetLat > 90.0
                || handoverEndLon < -180.0 || handoverEndLon > 180.0
                || handoverEndLat < -90.0 || handoverEndLat > 90.0
                || returnTargetLon < -180.0 || returnTargetLon > 180.0
                || returnTargetLat < -90.0 || returnTargetLat > 90.0) {
            throw new IllegalArgumentException("三段几何坐标的经纬度超出合法范围");
        }
        if (MissionTrackCommand.isSamePoint(nestLon, nestLat, meetLon, meetLat)
                || MissionTrackCommand.isSamePoint(meetLon, meetLat, handoverEndLon, handoverEndLat)
                || MissionTrackCommand.isSamePoint(handoverEndLon, handoverEndLat, returnTargetLon, returnTargetLat)) {
            throw new IllegalArgumentException("相邻航段端点不得重合（退化航段）");
        }
        if (!Double.isFinite(cruiseAltM) || !(cruiseAltM > (double) 0)) {
            throw new IllegalArgumentException("巡航高度须为正的有限数，实际 " + cruiseAltM);
        }
        if (waypointsPerLeg < 2 || waypointsPerLeg > GenerateRouteCommand.MAX_WAYPOINT_COUNT) {
            throw new IllegalArgumentException(
                    "waypointsPerLeg 须在 [2, " + GenerateRouteCommand.MAX_WAYPOINT_COUNT + "]，实际 " + waypointsPerLeg);
        }
        if (!Double.isFinite(outboundSpeedMs) || !Double.isFinite(terminalSpeedMs)
                || !Double.isFinite(returnSpeedMs)
                || outboundSpeedMs < MissionTrackCommand.MIN_SPEED_MS || terminalSpeedMs < MissionTrackCommand.MIN_SPEED_MS
                || returnSpeedMs < MissionTrackCommand.MIN_SPEED_MS
                || outboundSpeedMs > MissionTrackCommand.MAX_SPEED_MS || terminalSpeedMs > MissionTrackCommand.MAX_SPEED_MS
                || returnSpeedMs > MissionTrackCommand.MAX_SPEED_MS) {
            throw new IllegalArgumentException(
                    "各段速度须在 [" + MissionTrackCommand.MIN_SPEED_MS + ", " + MissionTrackCommand.MAX_SPEED_MS
                            + "] m/s 且为有限数，实际 outboundSpeedMs=" + outboundSpeedMs
                            + ", terminalSpeedMs=" + terminalSpeedMs + ", returnSpeedMs=" + returnSpeedMs);
        }
        if (takeoffMs <= 0L) {
            throw new IllegalArgumentException("起飞时刻必须为正，实际 " + takeoffMs);
        }
        if (takeoffMs > MissionTrackCommand.MAX_EPOCH_MS) {
            throw new IllegalArgumentException("起飞时刻超出合理纪元上界，实际 " + takeoffMs);
        }
        double totalSec = GeoApprox.distanceM(nestLon, nestLat, meetLon, meetLat) / outboundSpeedMs
                + GeoApprox.distanceM(meetLon, meetLat, handoverEndLon, handoverEndLat) / terminalSpeedMs
                + GeoApprox.distanceM(handoverEndLon, handoverEndLat, returnTargetLon, returnTargetLat) / returnSpeedMs;
        if (takeoffMs + Math.round(totalSec * 1000.0) > MissionTrackCommand.MAX_EPOCH_MS) {
            throw new IllegalArgumentException("三段航程累计结束时刻超出合理纪元上界，实际起飞时刻 " + takeoffMs
                    + "、累计时长秒 " + totalSec);
        }
    }





    /**
     * 端点重合判定：含极点等价（±90° 处任意经度为同一点）与 ±180° 经线等价
     */
    private static boolean isSamePoint(double lonA, double latA, double lonB, double latB) {
        if (Math.abs(latA - latB) > 1e-9) {
            return false;
        }
        if (Math.abs(latA) >= 90.0 - 1e-9) {
            return true;
        }
        double dLon = Math.abs(lonA - lonB) % 360.0;
        return dLon < 1e-9 || 360.0 - dLon < 1e-9;
    }
}
