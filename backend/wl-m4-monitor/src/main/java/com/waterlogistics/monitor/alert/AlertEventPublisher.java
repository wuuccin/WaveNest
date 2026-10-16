package com.waterlogistics.monitor.alert;

import com.waterlogistics.contract.mq.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * alert.event MQ 发送器（M4 → M2/M5）。
 *
 * <p>主题名取自 {@link TopicName#ALERT_EVENT}（{@code alert.event}，《22》§1.2 唯一权威）。
 * 发送失败不阻断检测节拍（下一帧继续），记警告留排障线索。
 */
@Component
public class AlertEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertEventPublisher.class);

    private final RabbitTemplate rabbit;

    /** 交换机名（与 M1 数据发布器同口径，demo 期用 topic 交换机） */
    @Value("${wavenest.mq.exchange:wavenest.data}")
    private String exchange;

    public AlertEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * 发送一条 alert.event 消息。
     *
     * @param event 告警事件（G-2 九字段）
     */
    public void publish(AlertRecorder.AlertEvent event) {
        try {
            rabbit.convertAndSend(exchange, TopicName.ALERT_EVENT, event);
        } catch (Exception e) {
            log.warn("alert.event publish failed: eventId={}, droneId={}, type={}, err={}",
                    event.eventId(), event.droneId(), event.alertType(), e.getMessage());
        }
    }
}
