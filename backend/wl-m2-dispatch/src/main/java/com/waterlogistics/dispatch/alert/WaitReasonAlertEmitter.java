package com.waterlogistics.dispatch.alert;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 等待原因透出告警（11-05 序号 2 宋冰鑫）。
 *
 * <p>监听 {@link OrderStateChangedEvent}，当订单等待原因从非等待变为
 * {@link WaitReason#WAIT_CARGO} 或 {@link WaitReason#WAIT_RESOURCE} 时，
 * 写一条 {@code alert} 记录并经 {@code alert.event} MQ 推送给前端告警列表。
 *
 * <p>三类等待告警口径：
 * <ul>
 *   <li>{@link WaitReason#WAIT_CARGO} → {@link AlertType#WAIT_CARGO_TIMEOUT}（装货超时）：
 *       订单因货物未就绪进入等待，货物所在巢时隙锁超时未释放；</li>
 *   <li>{@link WaitReason#WAIT_RESOURCE} → {@link AlertType#WAIT_RESOURCE}（等待资源）：
 *       订单因资源不足转入等待队列；</li>
 *   <li>货到巢超时 → {@link AlertType#CARGO_NEST_TIMEOUT}（货到巢超时）：
 *       由 {@link #emitCargoNestTimeout(String)} 主动调用，触发源在张睿哲序号 2 的货到巢关逻辑。</li>
 * </ul>
 *
 * <p>去重口径：同一订单同一等待原因在一次等待窗口内只发一条告警；当 waitReason 离开该值
 * （变为 null 或其他等待原因）时清除去重标记，下次重新进入会再发。
 *
 * <p>边界：沿用 {@code alert} 表现有列，不新增表、不新增列；{@code alert_type} 取
 * {@link AlertType} 中文取值，{@code level} 取 {@link AlertLevel#WARN}。
 */
@Component
public class WaitReasonAlertEmitter {

    private static final Logger log = LoggerFactory.getLogger(WaitReasonAlertEmitter.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 已发告警的去重标记：key = orderId + "|" + waitReason */
    private final Set<String> emitted = ConcurrentHashMap.newKeySet();

    private final AlertMapper alertMapper;
    private final OrderTaskMapper orderTaskMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${wavenest.mq.exchange:wavenest.data}")
    private String exchange;

    public WaitReasonAlertEmitter(AlertMapper alertMapper,
            OrderTaskMapper orderTaskMapper,
            RabbitTemplate rabbitTemplate) {
        this.alertMapper = alertMapper;
        this.orderTaskMapper = orderTaskMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 订单状态变更后检查等待原因。
     *
     * <p>使用 {@link TransactionalEventListener} 的 AFTER_COMMIT 相位，确保订单状态与
     * wait_reason 已落库后再写 alert，避免回滚导致 alert 孤儿行。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        WaitReason reason = event.view().waitReason();
        String orderId = event.view().orderId();

        if (reason == WaitReason.WAIT_CARGO) {
            emitIfAbsent(orderId, WaitReason.WAIT_CARGO, AlertType.WAIT_CARGO_TIMEOUT);
        } else if (reason == WaitReason.WAIT_RESOURCE) {
            emitIfAbsent(orderId, WaitReason.WAIT_RESOURCE, AlertType.WAIT_RESOURCE);
        } else {
            // 离开等待态：清除所有等待原因的去重标记，下次进入可重新告警
            clearEmitted(orderId);
        }
    }

    /**
     * 货到巢超时告警入口。
     *
     * <p>触发源在张睿哲序号 2 的货到巢关与锁定释放逻辑：货物已到巢但超时未被无人机取走时，
     * 由其调用本方法写一条 {@link AlertType#CARGO_NEST_TIMEOUT} 告警。
     *
     * @param orderId 订单标识
     */
    public void emitCargoNestTimeout(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        String key = dedupKey(orderId, AlertType.CARGO_NEST_TIMEOUT);
        if (!emitted.add(key)) {
            return;
        }
        emit(orderId, AlertType.CARGO_NEST_TIMEOUT);
    }

    /** 同一订单同一等待原因在一次等待窗口内只发一条。 */
    private void emitIfAbsent(String orderId, WaitReason reason, AlertType type) {
        String key = dedupKey(orderId, type);
        if (!emitted.add(key)) {
            return;
        }
        emit(orderId, type);
    }

    private void emit(String orderId, AlertType type) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        String droneId = order == null ? null : order.getDroneId();
        long now = System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();

        AlertEntity entity = new AlertEntity();
        entity.setAlertId(eventId);
        entity.setDroneId(droneId);
        entity.setOrderId(orderId);
        entity.setAlertType(type.getValue());
        entity.setLevel(AlertLevel.WARN.getValue());
        entity.setTriggerTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), DB_ZONE));
        entity.setHandleStatus("未处理");
        try {
            alertMapper.insert(entity);
        } catch (Exception e) {
            log.warn("等待原因告警落库失败（fail-soft）：orderId={} type={} err={}",
                    orderId, type.getValue(), e.getMessage());
        }

        AlertEventMessage message = new AlertEventMessage(
                eventId,
                type.getValue(),
                AlertLevel.WARN.getValue(),
                0,
                droneId,
                orderId,
                now,
                null,
                null,
                null,
                null);
        publish(message);
        log.info("等待原因告警已发出：orderId={} type={} droneId={} eventId={}",
                orderId, type.getValue(), droneId, eventId);
    }

    /**
     * 发送 alert.event MQ 消息。
     *
     * <p>抽成 protected 方法供单元测试覆盖，避免测试起 RabbitTemplate 连接。
     */
    protected void publish(AlertEventMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchange, TopicName.ALERT_EVENT, message);
        } catch (Exception e) {
            log.warn("等待原因告警 MQ 发送失败（fail-soft）：orderId={} type={} err={}",
                    message.orderId(), message.alertType(), e.getMessage());
        }
    }

    private void clearEmitted(String orderId) {
        emitted.removeIf(key -> key.startsWith(orderId + "|"));
    }

    private static String dedupKey(String orderId, AlertType type) {
        return orderId + "|" + type.name();
    }
}
