package com.waterlogistics.algo.meeting.model;

/**
 * 交接完成确认（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》§2.3
 * 第（6）项一字不改。
 *
 * <p>边界（§2.3／§3.6）：只给<b>实际值与计划基准</b>——投送结论由 M4 记录，可行性判定
 * 归 M2／G；<b>实际会面点、计划会面点与交接时刻是 G 功能 19 计算
 * {@code d_space = Haversine(实际会面点, 计划会面点)} 的输入源</b>，计划基准须原样保留。
 *
 * <p>首版口径：判定失败时 {@code handoverDoneT} 与三个开销字段为 {@code null}
 * （凡已完成接近的失败，{@code actualMeet} 仍填实值，供追溯）。
 *
 * @param taskId           任务标识
 * @param droneId          执行机实例
 * @param actualMeetLon    实际会面点经度（未完成接近时为 null）
 * @param actualMeetLat    实际会面点纬度（未完成接近时为 null）
 * @param plannedMeetLon   计划会面点经度（G 组 {@code d_space} 比对基准，原样保留）
 * @param plannedMeetLat   计划会面点纬度
 * @param plannedMeetT     计划会面时刻，Unix 毫秒（供 G 计算时间偏差）
 * @param handoverDoneT    交接完成时刻，Unix 毫秒（失败为 null）
 * @param escortTimeCost   伴飞时间开销，秒（失败为 null）
 * @param handoverTimeCost 交接时间开销，秒（失败为 null）
 * @param energyCost       伴飞 ＋ 交接能耗开销，满电百分比（失败为 null）
 * @param handoverResult   交接结果：成功／失败（失败含重试／悬停等待结论）
 */
public record B_HandoverConfirmation(

        String taskId,

        String droneId,

        Double actualMeetLon,

        Double actualMeetLat,

        double plannedMeetLon,

        double plannedMeetLat,

        long plannedMeetT,

        Long handoverDoneT,

        Integer escortTimeCost,

        Integer handoverTimeCost,

        Double energyCost,

        HandoverResult handoverResult) {
}
