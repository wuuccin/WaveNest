package com.waterlogistics.dispatch.alert;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「订单待处置」告警（M2 · 张睿哲 · 11-19 序号 1-2）。
 *
 * <p>触发点只有一个：{@code CargoGateWaker} 判定货巢时隙锁宽限期到期（或锁已不存在）、
 * 把订单转入 {@code ACCEPTED_WAIT_WAKE + WAIT_RESOURCE} 之后。这类单不会再被自动推进——
 * 资源释放时机不由门关掌握（见 11-05 定稿），必须有人或无人值守的自动重试来处置，
 * 因此发一条<b>可处置</b>的告警，和「等待资源」这条状态性告警区分开。
 *
 * <p><b>与「等待资源」告警的关系</b>：{@code WaitReasonAlertEmitter}（11-05 宋冰鑫）
 * 监听状态迁移、按等待原因发 {@code 等待资源}；本类由转等待的同一动作直接调用，
 * 发 {@code 订单待处置}。前者描述「在等什么」，后者描述「需要处置」，两者不互相替代。
 *
 * <p><b>去重</b>：本类不加内存去重。{@code CargoGateWaker} 只扫 {@code WAIT_CARGO} 的单，
 * 迁移后等待原因变成 {@code WAIT_RESOURCE}，下一拍即离开扫描集，一段等待窗口内只会调用一次。
 *
 * <p><b>告警类型取值</b>：11-19 序号 1-3（宋冰鑫）在 {@code AlertType} 新增该类型，
 * 本批先以常量持有同一中文取值，待 1-3 合入后改为引用 enum 取值，不改报文口径。
 */
@Component
public class ResourceWaitAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResourceWaitAlertPublisher.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 「订单待处置」的告警类型取值（与 1-3 在 {@code AlertType} 登记的文案一致）。 */
    public static final String ALERT_TYPE_ORDER_PENDING = "订单待处置";

    private static final String UNHANDLED = "未处理";

    private final AlertMapper alertMapper;
    private final OrderTaskMapper orderTaskMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${wavenest.mq.exchange:wavenest.data}")
    private String exchange;

    public ResourceWaitAlertPublisher(AlertMapper alertMapper,
            OrderTaskMapper orderTaskMapper,
            RabbitTemplate rabbitTemplate) {
        this.alertMapper = alertMapper;
        this.orderTaskMapper = orderTaskMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发出「订单待处置」告警。
     *
     * <p>落库为 fail-soft：告警失败不能回滚主链路的状态迁移（订单已经转入资源等待，
     * 回滚反而会丢事实）。与 {@code WaitReasonAlertEmitter} 同一口径。
     *
     * @param orderId 订单标识
     * @param detail  触发说明，写进日志便于现场对账
     */
    public void publishOrderPending(String orderId, String detail) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        String droneId = order == null ? null : order.getDroneId();
        long now = System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();

        AlertEntity entity = new AlertEntity();
        entity.setAlertId(eventId);
        entity.setDroneId(droneId);
        entity.setOrderId(orderId);
        entity.setAlertType(ALERT_TYPE_ORDER_PENDING);
        entity.setLevel(AlertLevel.WARN.getValue());
        entity.setTriggerTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), DB_ZONE));
        entity.setHandleStatus(UNHANDLED);
        try {
            alertMapper.insert(entity);
        } catch (Exception e) {
            log.warn("订单待处置告警落库失败（fail-soft）：orderId={} err={}", orderId, e.getMessage());
        }

        AlertEventMessage message = new AlertEventMessage(
                eventId,
                ALERT_TYPE_ORDER_PENDING,
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
        log.info("订单待处置告警已发出：orderId={} detail={} eventId={}", orderId, detail, eventId);
    }

    /**
     * 发送 alert.event MQ 消息；抽成 protected 方法供单元测试覆盖。
     */
    protected void publish(AlertEventMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchange, TopicName.ALERT_EVENT, message);
        } catch (Exception e) {
            log.warn("订单待处置告警 MQ 发送失败（fail-soft）：orderId={} err={}",
                    message.orderId(), e.getMessage());
        }
    }
}
