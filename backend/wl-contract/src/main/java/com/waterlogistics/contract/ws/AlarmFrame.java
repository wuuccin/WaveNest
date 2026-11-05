package com.waterlogistics.contract.ws;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 告警帧 {@code alarm}——依据《22-接口契约》v1.2 §4。
 *
 * <p>前端表现：实体红色闪烁 ＋ 事件流追加；同时由 M4 经 {@code alert.event} 走 MQ 双推。
 *
 * <p><b>T1-1 扩字段</b>（10-27 契约与地基）：新增位置／订单号／事件号三项，均为可空——
 * <b>取不到就留空，不许造假坐标</b>（告警目标的当前位置由实时出口投影取值，见 T1-2／T1-3）。
 *
 * @param alertType 告警类型（五类），见 {@link AlertType}
 * @param alertLevel 告警级别，见 {@link AlertLevel}
 * @param target    告警目标标识（无人机／订单标识等）
 * @param message   提示语
 * @param lon       告警目标位置-经度；取不到（如失联且无最后已知位置）为 {@code null}
 * @param lat       告警目标位置-纬度；同 {@code lon}
 * @param orderId   所属订单号；无任务时为 {@code null}（无任务时为空是正确值）
 * @param eventId   告警事件号，与 {@code alert.event} 消息的 {@code eventId} 同源
 */
public record AlarmFrame(

        AlertType alertType,

        AlertLevel alertLevel,

        String target,

        String message,

        Double lon,

        Double lat,

        String orderId,

        String eventId) {

    /**
     * 兼容构造器：保留 T1-1 之前的四参签名，新字段留空。
     *
     * <p>构造点分散在三个模块，序号 2 填值前各构造点继续用本构造器过渡，
     * 逐处迁移到全参构造器，互不阻塞。
     */
    public AlarmFrame(AlertType alertType, AlertLevel alertLevel,
                      String target, String message) {
        this(alertType, alertLevel, target, message, null, null, null, null);
    }
}
