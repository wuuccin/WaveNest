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
 * @param samples        末段采样（时间严格升序；构造时防御拷贝，外部再改不影响本值）
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
        samples = List.copyOf(Objects.requireNonNull(samples, "samples 不能为空"));
    }
}
