package com.waterlogistics.algo.meeting.model;

import java.util.List;
import java.util.Objects;

/**
 * 可飞航迹（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》§2.3 第（2）项
 * 一字不改；去程与返程复用同一项能力（同结构、两次调用）。
 *
 * <p>标识口径（《81》§2.3 口径 4）：{@code routeId} 是<b>对象标识</b>（不随重算变化），
 * {@code routeVersion} 是<b>版本标识</b>（重算即换新）；判新旧一律看版本字段。
 * {@code B_ConflictResult.checksumVersion} 与 F 组 {@code remaining_route_result.route_version}
 * 均指向本字段。
 *
 * <p>消费边界：C 组只用 {@code waypoints} 计算航迹长度做第二关粗估；
 * {@code tauFine}／{@code energyExact} 属精算账，C 组不消费（两笔账不混，§2.3 口径 3）。
 * {@code pathRisk} 取值范围 [0,1] 无量纲（《22-接口契约》§2.1.2）。
 *
 * @param routeId           航迹标识（对象标识）
 * @param routeVersion      航迹版本（重算即换新）
 * @param comboId           绑定的候选组合标识
 * @param waypoints         航点序列，按时刻严格升序（相邻航点时刻不得相同）
 * @param tauFine           精算飞行时长 τ_fine，秒（精算账）
 * @param energyExact       精确能耗，满电百分比（精算账）
 * @param pathRisk          路径风险 [0,1]，供 D 组风险项
 * @param landingPointsAlong 沿途可用迫降点标注（仅标注；落点由 E 功能 10 给定——红线③）
 * @param avoidZones        本航迹实际避让的禁飞区／桥梁净空标识（取证用）
 */
public record B_FlyableRoute(

        String routeId,

        String routeVersion,

        String comboId,

        List<Waypoint> waypoints,

        int tauFine,

        double energyExact,

        double pathRisk,

        List<String> landingPointsAlong,

        List<String> avoidZones) {

    public B_FlyableRoute {
        Objects.requireNonNull(routeId, "routeId 不能为空");
        Objects.requireNonNull(routeVersion, "routeVersion 不能为空");
        Objects.requireNonNull(comboId, "comboId 不能为空");
        if (routeId.isBlank() || routeVersion.isBlank() || comboId.isBlank()) {
            throw new IllegalArgumentException("routeId/routeVersion/comboId 不能为空白字符串");
        }
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("waypoints 至少需要 2 个航点：C 组据其粗估航迹长度，单点航迹长度为 0");
        }
        try {
            waypoints = List.copyOf(waypoints);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("waypoints 不能包含空元素", e);
        }
        for (int i = 1; i < waypoints.size(); i++) {
            if (waypoints.get(i).t() <= waypoints.get(i - 1).t()) {
                throw new IllegalArgumentException("waypoints 须按时刻严格升序，下标 " + i + " 处不满足");
            }
        }
        landingPointsAlong = Objects.requireNonNull(landingPointsAlong,
                "landingPointsAlong 不能为空，无标注时请传 List.of()");
        avoidZones = Objects.requireNonNull(avoidZones, "avoidZones 不能为空，无避让时请传 List.of()");
        try {
            landingPointsAlong = List.copyOf(landingPointsAlong);
            avoidZones = List.copyOf(avoidZones);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含 null", e);
        }
        if (landingPointsAlong.stream().anyMatch(String::isBlank)
                || avoidZones.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("landingPointsAlong/avoidZones 不能包含空白标识");
        }
        if (tauFine <= 0) {
            throw new IllegalArgumentException("精算飞行时长必须为正，实际 " + tauFine);
        }
        if (!Double.isFinite(energyExact) || energyExact < 0 || energyExact > 100.0) {
            throw new IllegalArgumentException("精确能耗须为 [0,100] 内的有限数（满电百分比），实际 " + energyExact);
        }
        if (!Double.isFinite(pathRisk) || pathRisk < 0.0 || pathRisk > 1.0) {
            throw new IllegalArgumentException("路径风险须在 [0,1]，实际 " + pathRisk);
        }
    }
}
