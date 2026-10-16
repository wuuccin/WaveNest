package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 告警检测结果（检测层 → 告警层）。
 *
 * <p>级别与类型一律使用契约枚举（《22-接口契约》v1.2 §2、《37-M6-数据库》§5.12）。
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
        long triggeredAtMs
) {
    public boolean hasAlert() { return level != null; }

    /** 无告警结果 */
    public static DetectionResult none(AlertType type) {
        return new DetectionResult(type, null, null, null, 0, false, 0);
    }
}
