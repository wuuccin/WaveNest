package com.waterlogistics.rendezvous.trajectory;

import java.util.List;
import java.util.Objects;

/**
 * 航迹生成入参——「一项能力、两次调用」的调用侧形态（《33-M3-路径规划》§4.1）：
 * 去程「机巢 → 会面点」、返程「当前位置 → 归巢落点」都用这一份入参。
 *
 * <p>阈值与参数一律由调用方注入（来源 {@code sys_param}／机型档案），本层不硬编码
 * （《81-B组算法方案》§2.4 硬约束 2）。
 *
 * @param comboId          候选组合标识
 * @param routeId          航迹对象标识（不随重算变化，由调用方设计）
 * @param startLon         起点经度
 * @param startLat         起点纬度
 * @param endLon           终点经度
 * @param endLat           终点纬度
 * @param cruiseAltM       巡航高度，米
 * @param speedMs          巡航速度（沿航迹地速），m/s；用于精算时长与航点内插
 * @param startMs          起点时刻（去程＝起飞时刻、返程＝返航起点时刻；须为正），Unix 毫秒
 * @param waypointCount    航点数（含首末端点，2–512；直线简化版按此均匀铺点）
 * @param landingPointsAlong 沿途可用迫降点标注（首版由调用方按台账预筛；检索半径过滤属精算版留桩）
 * @param avoidZones       实际避让的禁飞区／桥梁净空标识（首版由调用方给，直线简化不实测避让）
 * @param energyRatePerSecPct 能耗率（%／s），供首版线性能耗模型 energy＝τ×率
 * @param pathRisk         路径风险 [0,1]（首版由调用方给）
 */
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

    private static final int MAX_WAYPOINT_COUNT = 512;

    public GenerateRouteCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        Objects.requireNonNull(routeId, "routeId 不能为空");
        if (comboId.isBlank() || routeId.isBlank()) {
            throw new IllegalArgumentException("comboId/routeId 不能为空白字符串");
        }
        Objects.requireNonNull(landingPointsAlong, "landingPointsAlong 不能为空");
        Objects.requireNonNull(avoidZones, "avoidZones 不能为空");
        if (landingPointsAlong.stream().anyMatch(Objects::isNull) || avoidZones.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含 null");
        }
        landingPointsAlong = List.copyOf(landingPointsAlong);
        avoidZones = List.copyOf(avoidZones);
        if (!Double.isFinite(startLon) || !Double.isFinite(startLat)
                || !Double.isFinite(endLon) || !Double.isFinite(endLat)) {
            throw new IllegalArgumentException("起终点坐标必须为有限数");
        }
        if (waypointCount < 2 || waypointCount > MAX_WAYPOINT_COUNT) {
            throw new IllegalArgumentException(
                    "航点数须在 [2, " + MAX_WAYPOINT_COUNT + "]，实际 " + waypointCount);
        }
        if (startMs <= 0) {
            throw new IllegalArgumentException("起点时刻必须为正，实际 " + startMs);
        }
        if (!Double.isFinite(speedMs) || !(speedMs > 0)) {
            throw new IllegalArgumentException("速度必须为正的有限数，实际 " + speedMs);
        }
        if (!Double.isFinite(cruiseAltM) || !(cruiseAltM >= 0)) {
            throw new IllegalArgumentException("巡航高度不能为负且须为有限数，实际 " + cruiseAltM);
        }
        if (!Double.isFinite(energyRatePerSecPct) || !(energyRatePerSecPct >= 0)) {
            throw new IllegalArgumentException("能耗率不能为负且须为有限数，实际 " + energyRatePerSecPct);
        }
        if (!(pathRisk >= 0 && pathRisk <= 1)) {
            throw new IllegalArgumentException("路径风险须在 [0,1]，实际 " + pathRisk);
        }
    }
}
