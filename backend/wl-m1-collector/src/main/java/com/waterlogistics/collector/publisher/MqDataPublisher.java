package com.waterlogistics.collector.publisher;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 数据发布器。
 *
 * <p>主题名一律取自 {@link TopicName}（22 §1.2 唯一权威），不私自增删、不写字符串字面量。
 * 验收口径：RabbitMQ 管理台 http://localhost:15672 能看到 {@code data.ais} 速率在涨。
 */
@Component
public class MqDataPublisher implements DataPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqDataPublisher.class);

    private final RabbitTemplate rabbit;

    /** 交换机名（demo 期用 topic 交换机；正式收口后如契约另有定名，改此处一处即可） */
    @Value("${wavenest.mq.exchange:wavenest.data}")
    private String exchange;

    public MqDataPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publishAis(AisMessage msg) {
        send(TopicName.DATA_AIS, msg);
    }

    @Override
    public void publishUavTelemetry(UavTelemetry msg) {
        send(TopicName.DATA_UAV_TELEMETRY, msg);
    }

    @Override
    public void publishWeather(WeatherData msg) {
        send(TopicName.DATA_WEATHER, msg);
    }

    private void send(String routingKey, Object payload) {
        try {
            rabbit.convertAndSend(exchange, routingKey, payload);
        } catch (Exception e) {
            // 发布失败不阻断采集节拍（下一节拍继续）；计数异常留排障线索
            log.warn("publish failed: routingKey={}, err={}", routingKey, e.getMessage());
        }
    }
}
