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

    @SuppressWarnings("MissingJavadoc")
    public HandoverCommand {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(droneId, "droneId 不能为空");
        Objects.requireNonNull(params, "params 不能为空");
        if (taskId.isBlank() || droneId.isBlank()) {
            throw new IllegalArgumentException("taskId/droneId 不能为空白字符串");
        }
        if (plannedMeetT <= 0L) {
            throw new IllegalArgumentException("计划会面时刻必须为正的 Unix 毫秒值，实际 " + plannedMeetT);
        }
        if (!Double.isFinite(plannedMeetLon) || !Double.isFinite(plannedMeetLat)
                || plannedMeetLon < -180.0 || plannedMeetLon > 180.0
                || plannedMeetLat < -90.0 || plannedMeetLat > 90.0) {
            throw new IllegalArgumentException("计划会面点坐标必须为有限数且经纬度在合法范围内");
        }
        List<HandoverSample> sampleCopy = Objects.requireNonNull(samples, "samples 不能为空");
        for (HandoverSample sample : sampleCopy) {
            if (sample == null) {
                throw new IllegalArgumentException("末段样本序列不得包含 null 元素");
            }
        }
        samples = List.copyOf(sampleCopy);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("末段样本序列不能为空");
        }
        int sampleCount = samples.size();
        for (int i = 1; i < sampleCount; i++) {
            if (samples.get(i).t() <= samples.get(i - 1).t()) {
                throw new IllegalArgumentException("样本时间必须严格升序，第 " + (i + 1) + " 个样本时间非增");
            }
        }
    }
}
