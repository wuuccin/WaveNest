package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 告警检测结果（检测层 → 告警层）。
 *
 * <p>级别与类型一律使用契约枚举（《22-接口契约》v1.2 §2、《37-M6-数据库》§5.12）。
 *
 * <p>{@link Phase} 表达本样本在告警生命周期中的相位，供告警层做去重与清除复位：
 * 同一告警首次触发 {@link Phase#RAISED}、级别变化 {@link Phase#LEVEL_CHANGED}、
 * 同级持续 {@link Phase#DURATION}、本样本解除 {@link Phase#CLEARED}、
 * 失联→灭失推定 {@link Phase#LOSS_DECLARED}（同级也强制上报一次，G-12）。
 */
public record DetectionResult(
        /** 告警类型 */
        AlertType alertType,
        /** 告警级别，null 表示无告警 */
        AlertLevel level,
        /** 当前检测值（偏航距离m / 电量% / 风速m/s；失联为 null） */
        Double currentValue,
        /** 触发阈值（m / % / m/s） */
        Double thresholdValue,
        /** 告警持续时长（秒），首次触发为 0 */
        long durationSeconds,
        /** 是否首次触发 */
        boolean newlyTriggered,
        /** 原始触发时间戳（毫秒） */
        long triggeredAtMs,
        /** 本样本的生命周期相位 */
        Phase phase) {

    /** 告警生命周期相位（检测层 → 告警层/编排层的信号契约）。 */
    public enum Phase {
        /** 无告警（常态空帧） */
        NONE,
        /** 首次触发（新事件，分配 eventId） */
        RAISED,
        /** 级别变化（升级即时／降级迟滞达标；复用同一 eventId） */
        LEVEL_CHANGED,
        /** 同级持续（duration 刷新，不产生新消息） */
        DURATION,
        /** 告警在本样本解除（编排层据此调 AlertRecorder.resolve） */
        CLEARED,
        /** 失联持续超过灭失窗口，推定灭失（同级亦强制上报一次，G-12） */
        LOSS_DECLARED
    }

    public boolean hasAlert() { return level != null; }

    /** 无告警结果（常态空帧） */
    public static DetectionResult none(AlertType type) {
        return new DetectionResult(type, null, null, null, 0, false, 0, Phase.NONE);
    }

    /** 告警解除结果：无级别，但相位为 CLEARED，区别于"从未有告警" */
    public static DetectionResult cleared(AlertType type) {
        return new DetectionResult(type, null, null, null, 0, false, 0, Phase.CLEARED);
    }
}
