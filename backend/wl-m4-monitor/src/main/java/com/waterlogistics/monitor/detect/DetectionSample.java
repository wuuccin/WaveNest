package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.measurement.RouteMeasurement;

/**
 * 检测样本：彭紫情 ingest/track 侧每帧清洗完成后推给 {@link DetectionSink} 的一帧数据。
 *
 * <p>衔接裁定（10-16 清单 §3.7）：宋定义 sink、彭定义 provider，双方只依赖接口不 import 实现类。
 * 可空字段表示该帧不具备对应检测条件，编排器跳过相应检测器，不产空告警。</p>
 *
 * @param droneId        无人机标识（必填）
 * @param orderId        当前订单标识（可空：失联早于订单绑定等场景）
 * @param batteryPercent 电量百分比 0–100（可空：该帧无电量时跳过低电量检测）
 * @param projection     F 组航迹投影（可空：无规划航迹/几何不可用时跳过偏航检测）
 * @param tsMs           遥测打点时间戳（Unix 毫秒）；同时作为心跳唯一来源（G-13）
 * @param missionActive  是否处于执行任务中/返航中（仅这两态做失联门控，地面机不误报）
 */
public record DetectionSample(
        String droneId,
        String orderId,
        Double batteryPercent,
        RouteMeasurement.Projection projection,
        long tsMs,
        boolean missionActive) {
}
