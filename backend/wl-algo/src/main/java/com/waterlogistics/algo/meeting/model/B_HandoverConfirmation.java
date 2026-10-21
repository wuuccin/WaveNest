package com.waterlogistics.algo.meeting.model;

import java.util.Objects;

/**
 * 交接完成确认（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》§2.3
 * 第（6）项一字不改。
 *
 * <p>边界（§2.3／§3.6）：只给<b>实际值与计划基准</b>——投送结论由 M4 记录，可行性判定
 * 归 M2／G；<b>实际会面点、计划会面点与交接时刻是 G 功能 19 计算
 * {@code d_space = Haversine(实际会面点, 计划会面点)} 的输入源</b>，计划基准须原样保留。
 *
 * <p>首版口径：判定失败时 {@code handoverDoneT} 与三个开销字段为 {@code null}
 * （凡已完成接近的失败，{@code actualMeet} 仍填实值，供追溯）。时间开销按整秒截断；
 * {@code energyCost} 按「首样本 → 交接完成」的精确毫秒跨度计算（不受整秒截断影响）。
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

    public B_HandoverConfirmation {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空或空白");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId 不能为空或空白");
        }
        Objects.requireNonNull(handoverResult, "handoverResult 不能为空");
        if ((actualMeetLon == null) != (actualMeetLat == null)) {
            throw new IllegalArgumentException("实际会面点经度与纬度须同时存在或同时为空");
        }
        requireCoordinate(plannedMeetLon, plannedMeetLat, "planned");
        if (plannedMeetT <= 0L) {
            throw new IllegalArgumentException("计划会面时刻须为正的 Unix 毫秒值，实际 " + plannedMeetT);
        }
        if (actualMeetLon != null) {
            requireCoordinate(actualMeetLon, actualMeetLat, "actual");
        }
        if (handoverDoneT != null && handoverDoneT <= 0L) {
            throw new IllegalArgumentException("交接完成时刻须为有效的 Unix 毫秒值，实际 handoverDoneT="
                    + handoverDoneT + "（taskId=" + taskId + "）");
        }
        switch (handoverResult) {
            case SUCCESS -> {
                if (handoverDoneT == null) {
                    throw new IllegalArgumentException("交接成功却缺交接完成时刻（taskId=" + taskId + "）");
                }
                if (actualMeetLon == null || actualMeetLat == null) {
                    throw new IllegalArgumentException("交接成功却缺实际会面点（taskId=" + taskId + "）");
                }
                if (escortTimeCost == null || handoverTimeCost == null || energyCost == null) {
                    throw new IllegalArgumentException("交接成功却缺开销字段（taskId=" + taskId + "）");
                }
            }
            case FAILED -> {
                if (handoverDoneT != null || escortTimeCost != null
                        || handoverTimeCost != null || energyCost != null) {
                    throw new IllegalArgumentException("交接失败却带完成时刻或开销字段（taskId=" + taskId + "）");
                }
            }

        }
        if (escortTimeCost != null && escortTimeCost < 0) {
            throw new IllegalArgumentException("伴飞时间开销不得为负，实际 " + escortTimeCost);
        }
        if (handoverTimeCost != null && handoverTimeCost < 0) {
            throw new IllegalArgumentException("交接时间开销不得为负，实际 " + handoverTimeCost);
        }
        if (energyCost != null
                && (!Double.isFinite(energyCost) || energyCost < 0.0 || energyCost > 100.0)) {
            throw new IllegalArgumentException("能耗开销须在 [0,100]，实际 " + energyCost);
        }
    }

    private static void requireCoordinate(Double lon, Double lat, String name) {
        if (lon == null || lat == null) {
            return;
        }
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(name + " 会面坐标必须为有限数且在合法范围内，实际 lon="
                    + lon + ", lat=" + lat);
        }
    }
}
