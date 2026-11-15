package com.waterlogistics.monitor.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.monitor.alert.AlertEventPublisher;
import com.waterlogistics.monitor.alert.AlertRecorder;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class WeatherDetectionListenerTest {

    private static final class RecordingPublisher extends AlertEventPublisher {
        private final List<AlertEventMessage> sent = new ArrayList<>();

        private RecordingPublisher() {
            super(null);
        }

        @Override
        public void publish(AlertEventMessage event) {
            sent.add(event);
        }
    }

    private final List<OrderTaskEntity> orders = new ArrayList<>();
    private RecordingPublisher publisher;
    private WeatherDetectionListener listener;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        listener = new WeatherDetectionListener(
                new WeatherViolationDetector(WeatherViolationDetectorTest.parameters()),
                new AlertRecorder(publisher), orderMapper());
    }

    @Test
    void 全域判档后按在飞订单分发并保持同级去重() {
        orders.add(order("ORD-1", "UAV-1", OrderStatus.OUTBOUND));
        orders.add(order("ORD-2", "UAV-2", OrderStatus.HANDOVER));

        listener.onWeatherMessage(weather(8.0, 1_000L));
        listener.onWeatherMessage(weather(8.0, 2_000L));

        assertEquals(2, publisher.sent.size());
        assertEvent(publisher.sent.get(0), "ORD-1", "UAV-1", "警告", 8.0);
        assertEvent(publisher.sent.get(1), "ORD-2", "UAV-2", "警告", 8.0);

        listener.onWeatherMessage(weather(8.0, 3_000L));
        listener.onWeatherMessage(weather(14.0, 4_000L));
        listener.onWeatherMessage(weather(14.0, 5_000L));

        assertEquals(4, publisher.sent.size());
        assertEvent(publisher.sent.get(2), "ORD-1", "UAV-1", "严重", 14.0);
        assertEvent(publisher.sent.get(3), "ORD-2", "UAV-2", "严重", 14.0);
        assertEquals(publisher.sent.get(0).eventId(), publisher.sent.get(2).eventId());
        assertEquals(publisher.sent.get(1).eventId(), publisher.sent.get(3).eventId());
    }

    @Test
    void 清除后再次超限会产生新事件() {
        orders.add(order("ORD-1", "UAV-1", OrderStatus.OUTBOUND));
        listener.onWeatherMessage(weather(8.0, 1_000L));

        for (int index = 2; index <= 7; index++) {
            listener.onWeatherMessage(weather(7.0, index * 1_000L));
        }
        assertEquals(1, publisher.sent.size());
        String firstEventId = publisher.sent.get(0).eventId();

        listener.onWeatherMessage(weather(8.0, 8_000L));
        listener.onWeatherMessage(weather(8.0, 9_000L));

        assertEquals(2, publisher.sent.size());
        assertNotEquals(firstEventId, publisher.sent.get(1).eventId());
    }

    @Test
    void 同一无人机切换到新订单时重新产生告警() {
        orders.add(order("ORD-OLD", "UAV-1", OrderStatus.OUTBOUND));
        listener.onWeatherMessage(weather(8.0, 1_000L));
        listener.onWeatherMessage(weather(8.0, 2_000L));
        String oldEventId = publisher.sent.get(0).eventId();

        orders.set(0, order("ORD-NEW", "UAV-1", OrderStatus.OUTBOUND));
        listener.onWeatherMessage(weather(8.0, 3_000L));

        assertEquals(2, publisher.sent.size());
        assertEquals("ORD-NEW", publisher.sent.get(1).orderId());
        assertNotEquals(oldEventId, publisher.sent.get(1).eventId());
    }

    @Test
    void 同一观测时刻的多个格点只判级一次且取最大风速() {
        orders.add(order("ORD-1", "UAV-1", OrderStatus.OUTBOUND));

        listener.onWeatherMessage(weather(8.0, 1_000L));
        listener.onWeatherMessage(weather(12.0, 1_000L));
        listener.onWeatherMessage(weather(10.0, 1_000L));

        assertEquals(0, publisher.sent.size(), "同一时刻未冲刷前不判级");

        listener.onWeatherMessage(weather(12.0, 2_000L));

        assertEquals(1, publisher.sent.size(), "一个观测时刻只产生一条事件");
        assertEquals("警告", publisher.sent.get(0).level());
        assertEquals(12.0, publisher.sent.get(0).currentValue(), "取格点最大风速");
    }

    @Test
    void 气象队列独立于M2并绑定既有主题() throws NoSuchMethodException {
        RabbitListener rabbitListener = WeatherDetectionListener.class
                .getMethod("onWeatherMessage", WeatherData.class)
                .getAnnotation(RabbitListener.class);
        QueueBinding binding = rabbitListener.bindings()[0];

        assertEquals("${wavenest.mq.m4-data-weather-queue:wavenest.m4.data-weather}",
                binding.value().name());
        assertEquals("${wavenest.mq.exchange:wavenest.data}", binding.exchange().name());
        assertTrue(List.of(binding.key()).contains(TopicName.DATA_WEATHER));
    }

    private void assertEvent(AlertEventMessage event, String orderId, String droneId,
            String level, double threshold) {
        assertEquals("气象超限", event.alertType());
        assertEquals(orderId, event.orderId());
        assertEquals(droneId, event.droneId());
        assertEquals(level, event.level());
        assertEquals(threshold, event.thresholdValue());
    }

    private OrderTaskMapper orderMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return new ArrayList<>(orders);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    private static OrderTaskEntity order(String orderId, String droneId, OrderStatus status) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(orderId);
        order.setDroneId(droneId);
        order.setStatus(status.getValue());
        return order;
    }

    private static WeatherData weather(double windSpeedMs, long observedAtMs) {
        return new WeatherData(115.86, 28.67, windSpeedMs, 90.0, windSpeedMs,
                observedAtMs, observedAtMs, observedAtMs);
    }
}
