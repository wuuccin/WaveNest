package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.GeoApprox;

import java.util.List;
import java.util.Objects;



/**
 * 航迹生成入参——「一项能力、两次调用」的调用侧形态（《33-M3-路径规划》§4.1）：
 * 去程「机巢 → 会面点」、返程「当前位置 → 归巢落点」都用这一份入参。
 *
 * <p>阈值与参数一律由调用方注入（来源 {@code sys_param}／机型档案），本层不硬编码
 * （《81-B组算法方案》§2.4 硬约束 2）。
 *
 * @param comboId             候选组合标识
 * @param routeId             航迹对象标识（不随重算变化，由调用方设计）
 * @param startLon            起点经度
 * @param startLat            起点纬度
 * @param endLon              终点经度
 * @param endLat              终点纬度
 * @param cruiseAltM          巡航高度，米
 * @param speedMs             巡航速度（沿航迹地速），m/s；用于精算时长与航点内插
 * @param startMs             起点时刻（去程＝起飞时刻、返程＝返航起点时刻；须为正），Unix 毫秒
 * @param waypointCount       航点数（含首末端点，2–512；直线简化版按此均匀铺点）
 * @param landingPointsAlong  沿途可用迫降点标注（首版由调用方按台账预筛；检索半径过滤属精算版留桩）
 * @param avoidZones          实际避让的禁飞区／桥梁净空标识（首版由调用方给，直线简化不实测避让）
 * @param energyRatePerSecPct 能耗率（%／s），供首版线性能耗模型 energy＝τ×率
 * @param pathRisk            路径风险 [0,1]（首版由调用方给）
 */
@SuppressWarnings("MissingJavadoc")
public record GenerateRouteCommand(

        String comboId,

        String routeId,

        double startLon,

        double startLat,

        double endLon,

        double endLat,

        double cruiseAltM,

        double speedMs,

        long startMs,

        int waypointCount,

        List<String> landingPointsAlong,

        List<String> avoidZones,

        double energyRatePerSecPct,

        double pathRisk) {

    static final int MAX_WAYPOINT_COUNT = 512;

    private static final int MAX_ANNOTATION_IDS = 512;

    /** 起点时刻上界：2100-01-01T00:00:00Z，为防止后续时刻加法溢出 */
    private static final long MAX_START_MS = 4_102_444_800_000L;





    public GenerateRouteCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        Objects.requireNonNull(routeId, "routeId 不能为空");
        if (comboId.isBlank() || routeId.isBlank()) {
            throw new IllegalArgumentException("comboId/routeId 不能为空白字符串");
        }
        Objects.requireNonNull(landingPointsAlong, "landingPointsAlong 不能为空");
        Objects.requireNonNull(avoidZones, "avoidZones 不能为空");
        for (String annotationId : landingPointsAlong) {
            if (annotationId == null) {
                throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含 null");
            }
        }
        for (String annotationId : avoidZones) {
            if (annotationId == null) {
                throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含 null");
            }
        }
        landingPointsAlong = List.copyOf(landingPointsAlong);
        avoidZones = List.copyOf(avoidZones);
        if (landingPointsAlong.size() > GenerateRouteCommand.MAX_ANNOTATION_IDS
                || avoidZones.size() > GenerateRouteCommand.MAX_ANNOTATION_IDS) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不得超过 " + GenerateRouteCommand.MAX_ANNOTATION_IDS + " 项");
        }
        if (landingPointsAlong.stream().anyMatch(String::isBlank)
                || avoidZones.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含空白标识");
        }
        if (landingPointsAlong.stream().distinct().count() != (long) landingPointsAlong.size()
                || avoidZones.stream().distinct().count() != (long) avoidZones.size()) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不得包含重复标识");
        }
        if (!Double.isFinite(startLon) || !Double.isFinite(startLat) || !Double.isFinite(endLon) || !Double.isFinite(endLat)) {
            throw new IllegalArgumentException("起终点坐标必须为有限数");
        }
        if (startLon < -180.0 || startLon > 180.0 || startLat < -90.0 || startLat > 90.0
                || endLon < -180.0 || endLon > 180.0 || endLat < -90.0 || endLat > 90.0) {
            throw new IllegalArgumentException("起终点经纬度超出地理范围");
        }
        if (GeoApprox.distanceM(startLon, startLat, endLon, endLat) < 1e-6) {
            throw new IllegalArgumentException("起终点不能重合（零长度航迹）");
        }
        if (waypointCount < 2 || waypointCount > GenerateRouteCommand.MAX_WAYPOINT_COUNT) {
            throw new IllegalArgumentException(
                    "航点数须在 [2, " + GenerateRouteCommand.MAX_WAYPOINT_COUNT + "]，实际 " + waypointCount);
        }
        if (startMs <= 0L || startMs > GenerateRouteCommand.MAX_START_MS) {
            throw new IllegalArgumentException("起点时刻须为正且不晚于 2100-01-01T00:00:00Z，实际 " + startMs);
        }
        if (!Double.isFinite(speedMs) || !(speedMs > (double) 0)) {
            throw new IllegalArgumentException("速度必须为正的有限数，实际 " + speedMs);
        }
        if (!Double.isFinite(cruiseAltM) || !(cruiseAltM > (double) 0)) {
            throw new IllegalArgumentException("巡航高度必须为正的有限数，实际 " + cruiseAltM);
        }
        if (!Double.isFinite(energyRatePerSecPct) || !(energyRatePerSecPct >= (double) 0)) {
            throw new IllegalArgumentException("能耗率不能为负且须为有限数，实际 " + energyRatePerSecPct);
        }
        if (!(pathRisk >= (double) 0 && pathRisk <= 1.0)) {
            throw new IllegalArgumentException("路径风险须在 [0,1]，实际 " + pathRisk);
        }
    }
}
