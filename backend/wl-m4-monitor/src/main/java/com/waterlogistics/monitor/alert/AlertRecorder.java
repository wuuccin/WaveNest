package com.waterlogistics.monitor.alert;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警记录与投递。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>写 alert 表（由 M6 承载，本模块只组装对象；落库经 wl-storage，留接口）</li>
 *   <li>发 alert.event（MQ 推 M2，帧交给 wl-app/push 推 WS）</li>
 * </ul>
 *
 * <p>15 号阶段：先把 alert.event 的 payload 组装好并留在内存流水；
 * 落库与 MQ 发送待链路接通后补齐（分工表：15 号先把量算出来，告警链 16 号接）。</p>
 *
 * <p>去重口径：按「无人机 ＋ 告警类型」追踪当前已发事件级别——仅<b>首次触发</b>或
 * <b>级别变化</b>时产生一条新事件；同级持续帧不重复入流水（否则每帧一条形成告警风暴）。
 * 告警清除的复位由接线层在检测器返回无告警时调 {@link #resolve} 完成（16 号接管道）。</p>
 */
public class AlertRecorder {

    /** alert.event 消息对象（G-2 权威 9 字段） */
    public record AlertEvent(
            String eventId,       // UUID，全链路唯一
            String alertType,     // 偏航/低电量/失联/气象超限（契约 AlertType 中文取值）
            String level,         // 警告/严重（契约 AlertLevel 中文取值）
            int duration,         // 持续时长（秒）
            String droneId,
            String orderId,
            long timestamp,       // 告警首次触发时刻（检测层 triggeredAtMs，Unix 毫秒）
            Double currentValue,  // 偏航距离m/电量%/风速m/s；失联为 null
            Double thresholdValue
    ) {}

    /** 已产生的 alert.event 列表（当前阶段放内存，落库接 wl-storage / M6） */
    private final List<AlertEvent> eventLog = new ArrayList<>();

    /** 活跃告警去重：key = 无人机|告警类型，value = 已发事件级别；同级持续不再产生事件 */
    private final Map<String, AlertLevel> activeLevel = new HashMap<>();

    private static String activeKey(String droneId, AlertType type) {
        return droneId + "|" + type.name();
    }

    /**
     * 记录一次告警检测结果。
     *
     * @param result  检测结果（级别/类型为契约枚举）
     * @param droneId 无人机标识
     * @param orderId 订单标识
     * @return 新产生的 AlertEvent；无告警或同级持续（去重）时返回 null
     */
    public AlertEvent record(DetectionResult result, String droneId, String orderId) {
        if (!result.hasAlert()) return null;

        String key = activeKey(droneId, result.alertType());
        AlertLevel previous = activeLevel.get(key);
        // 仅首次触发 / 级别变化产生事件；同级持续帧只体现在检测层的 duration 上，不重复入流水
        if (previous != null && previous == result.level()) {
            return null;
        }
        activeLevel.put(key, result.level());

        AlertEvent event = new AlertEvent(
                UUID.randomUUID().toString(),
                result.alertType().getValue(),
                result.level().getValue(),
                (int) result.durationSeconds(),
                droneId,
                orderId,
                result.triggeredAtMs(),
                result.currentValue(),
                result.thresholdValue()
        );
        eventLog.add(event);
        // TODO 16 号：落库与投递——写 alert 表（wl-storage）＋发 alert.event（MQ → wl-app/push WS）
        return event;
    }

    /**
     * 告警清除复位：16 号接线层在检测器返回无告警时按机/类型调用，
     * 复位后同型告警再次越阈可重新产生事件。
     */
    public void resolve(String droneId, AlertType alertType) {
        activeLevel.remove(activeKey(droneId, alertType));
    }

    /** 获取告警事件流水（当前阶段用，替代 alert 表查询）。 */
    public List<AlertEvent> eventLog() { return Collections.unmodifiableList(eventLog); }
}
