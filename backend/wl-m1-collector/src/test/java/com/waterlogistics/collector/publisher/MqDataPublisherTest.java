package com.waterlogistics.collector.publisher;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MqDataPublisher 单测：验证三类数据走契约常量主题，且发送异常不阻断采集。
 */
class MqDataPublisherTest {

    /** 记录 convertAndSend 调用的假模板（继承以复用真实签名，不启动连接） */
    private static final class RecordingTemplate extends RabbitTemplate {
        private final List<String> routingKeys = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private boolean failNext;

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            if (failNext) {
                throw new IllegalStateException("broker down");
            }
            routingKeys.add(routingKey);
            payloads.add(object);
        }
    }

    private static MqDataPublisher publisherWith(RecordingTemplate template) {
        MqDataPublisher publisher = new MqDataPublisher(template);
        // 生产代码读 @Value，单测直接反射注入默认交换机名，避免起 Spring 容器
        try {
            var field = MqDataPublisher.class.getDeclaredField("exchange");
            field.setAccessible(true);
            field.set(publisher, "wavenest.data");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return publisher;
    }

    @Test
    @DisplayName("AIS 走 data.ais 主题（不是 data.ship_ais）")
    void publishAisUsesContractTopic() {
        RecordingTemplate template = new RecordingTemplate();
        publisherWith(template).publishAis(new AisMessage("123456789", "SIM-SHIP-01",
                114.30, 30.60, 8.0, 90.0, 1_000L));

        assertEquals(1, template.routingKeys.size());
        assertEquals(TopicName.DATA_AIS, template.routingKeys.get(0));
        assertTrue(template.payloads.get(0) instanceof AisMessage);
    }

    @Test
    @DisplayName("遥测走 data.uav_telemetry 主题")
    void publishTelemetryUsesContractTopic() {
        RecordingTemplate template = new RecordingTemplate();
        publisherWith(template).publishUavTelemetry(new UavTelemetry("UAV-01",
                114.308, 30.605, 80.0, 12.0, 92.0, 45.0, 1_000L));

        assertEquals(TopicName.DATA_UAV_TELEMETRY, template.routingKeys.get(0));
    }

    @Test
    @DisplayName("气象走 data.weather 主题")
    void publishWeatherUsesContractTopic() {
        RecordingTemplate template = new RecordingTemplate();
        publisherWith(template).publishWeather(new WeatherData(
                114.29, 30.59, 5.0, 135.0, 8.0, 1_000L, 1_000L, 1_000L));

        assertEquals(TopicName.DATA_WEATHER, template.routingKeys.get(0));
    }

    @Test
    @DisplayName("MQ 故障时发布不抛异常（下一节拍继续采）")
    void publishFailureDoesNotBreakTick() {
        RecordingTemplate template = new RecordingTemplate();
        template.failNext = true;
        MqDataPublisher publisher = publisherWith(template);

        assertDoesNotThrow(() -> publisher.publishAis(new AisMessage("1", "s",
                114.0, 30.0, 8.0, 90.0, 1L)));
        assertTrue(template.routingKeys.isEmpty(), "失败时不应记录为已发送");
    }
}
