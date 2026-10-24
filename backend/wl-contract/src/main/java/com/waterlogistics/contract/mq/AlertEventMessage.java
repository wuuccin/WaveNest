package com.waterlogistics.contract.mq;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 告警事件 {@code alert.event} —— <b>M4 → M2、M5</b>，依据《37》§5.12 与《86》G-2 九字段。
 *
 * <p>生产方 M4（检测层 {@code AlertRecorder} 记录并投递），消费方 M2（告警驱动重调度／返航复核）、
 * M5（经组装层 {@code RealtimeEventBridge} 投影为 WS {@code alarm} 帧）。
 *
 * <h3>与检测层内部结构的关系</h3>
 *
 * <p>本对象是检测层内部结构 {@code AlertRecorder.AlertEvent} 的<b>契约层同构替换</b>：
 * 九个字段的<b>名称、类型、顺序逐项一致</b>，切换时只换类型，<b>字段取值一律原样</b>。
 * 跨模块一律只认本契约对象（口径纪律第 4 条），不得在调度侧照抄一份监控侧的结构。
 *
 * <h3>字段取值口径</h3>
 *
 * <ul>
 *   <li>{@code alertType} 取 {@link AlertType} 的中文取值：失联／低电量／偏航／气象超限／链路异常</li>
 *   <li>{@code level} 取 {@link AlertLevel} 的中文取值：提示／警告／严重</li>
 *   <li>{@code eventId} 同一告警一个 id 贯穿始终（UUID）；级别变化与灭失推定复用原 id，
 *       告警解除复位后再次越阈才换新 id</li>
 *   <li>{@code timestamp} 取检测层<b>首次触发</b>时刻（非本次发送时刻），Unix 毫秒</li>
 * </ul>
 *
 * <h3>落库口径（与消息口径的差异）</h3>
 *
 * <p>{@code alert} 表只有八列，<b>不含</b> {@code duration}／{@code currentValue}／{@code thresholdValue}
 * 三列——这三字段只随本消息携带，<b>不落库、不拼文本序列化</b>。落库侧按
 * {@code alert_id} 复用 {@code eventId}，{@code trigger_time} 由 {@code timestamp} 按
 * Asia/Shanghai 折算，{@code handle_status} 取「未处理」。
 *
 * @param eventId        告警事件唯一标识（UUID），同一告警贯穿始终
 * @param alertType      告警类型（{@link AlertType} 中文取值：失联／低电量／偏航／气象超限／链路异常）
 * @param level          告警级别（{@link AlertLevel} 中文取值：提示／警告／严重）
 * @param duration       持续时长（秒），由检测层逐帧刷新；同级持续帧不重复发事件
 * @param droneId        无人机标识
 * @param orderId        订单标识，<b>可为 {@code null}</b>（如失联早于订单绑定）
 * @param timestamp      告警首次触发时刻，Unix 毫秒
 * @param currentValue   当前值：偏航距离（m）／电量（%）／风速（m/s）；<b>失联时为 {@code null}</b>
 * @param thresholdValue 阈值，与 {@code currentValue} 同量纲；<b>可为 {@code null}</b>
 */
public record AlertEventMessage(
        String eventId,
        String alertType,
        String level,
        int duration,
        String droneId,
        String orderId,
        long timestamp,
        Double currentValue,
        Double thresholdValue) {

    /** 本对象对应的 MQ 主题 */
    public static final String TOPIC = TopicName.ALERT_EVENT;
}
