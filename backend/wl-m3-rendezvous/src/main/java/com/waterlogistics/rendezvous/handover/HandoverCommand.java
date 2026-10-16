package com.waterlogistics.rendezvous.handover;

import java.util.List;
import java.util.Objects;

/**
 * 末段判定入参——计划会面基准（来自会面点解，供 G 组 {@code d_space} 比对，原样保留）
 * ＋末段采样序列＋阈值。
 *
 * @param taskId         任务标识
 * @param droneId        执行机实例
 * @param plannedMeetLon 计划会面点经度（取自 B_RendezvousSolution 对应组合）
 * @param plannedMeetLat 计划会面点纬度
 * @param plannedMeetT   计划会面时刻，Unix 毫秒
 * @param samples        末段采样（非空且时间严格升序——构造期即拒绝违规；构造时防御拷贝）
 * @param params         判定阈值（调用方注入）
 */
public record HandoverCommand(

        String taskId,

        String droneId,

        double plannedMeetLon,

        double plannedMeetLat,

        long plannedMeetT,

        List<HandoverSample> samples,

        HandoverParams params) {

    public HandoverCommand {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(droneId, "droneId 不能为空");
        Objects.requireNonNull(params, "params 不能为空");
        if (!Double.isFinite(plannedMeetLon) || !Double.isFinite(plannedMeetLat)) {
            throw new IllegalArgumentException("计划会面点坐标必须为有限数");
        }
        samples = List.copyOf(Objects.requireNonNull(samples, "samples 不能为空"));
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("末段样本序列不能为空");
        }
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).t() <= samples.get(i - 1).t()) {
                throw new IllegalArgumentException("样本时间必须严格升序，第 " + i + " 个样本时间非增");
            }
        }
    }
}
