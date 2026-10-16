package com.waterlogistics.algo.meeting.model;

import java.util.List;

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
 * @param waypoints         航点序列，时间升序
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
}
