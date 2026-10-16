package com.waterlogistics.monitor.detect;

/**
 * 检测汇（10-16 清单 §3.7 衔接裁定：本接口由宋冰鑫定义并实现，彭紫情侧依赖）。
 *
 * <p>彭紫情 ingest/track 侧在每帧遥测清洗、F 组量测投影完成后调用 {@link #onSample}，
 * M4 内部完成偏航/低电量/心跳三检测、判级去抖、告警组装与投递；
 * 调用方不感知检测器与告警生命周期。任务结束调 {@link #onMissionEnd} 复位。</p>
 *
 * <p>实现必须线程安全（多无人机帧可能来自不同消费线程），且不抛异常打断调用方采集节拍。</p>
 */
public interface DetectionSink {

    /**
     * 喂入一帧检测样本（偏航投影可空、电量可空，缺哪项跳过哪项检测；ts 必作心跳）。
     */
    void onSample(DetectionSample sample);

    /**
     * 任务结束/换班：复位该无人机的全部检测器与活跃告警跟踪，下一单重新起事件。
     */
    void onMissionEnd(String droneId);
}
