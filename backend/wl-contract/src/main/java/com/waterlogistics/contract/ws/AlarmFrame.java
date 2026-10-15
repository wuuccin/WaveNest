package com.waterlogistics.contract.ws;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 告警帧 {@code alarm}——依据《22-接口契约》v1.2 §4。
 *
 * <p>前端表现：实体红色闪烁 ＋ 事件流追加；同时由 M4 经 {@code alert.event} 走 MQ 双推。
 *
 * @param alertType 告警类型（五类），见 {@link AlertType}
 * @param alertLevel 告警级别，见 {@link AlertLevel}
 * @param target    告警目标标识（无人机／订单标识等）
 * @param message   提示语
 */
public record AlarmFrame(

        AlertType alertType,

        AlertLevel alertLevel,

        String target,

        String message) {
}
