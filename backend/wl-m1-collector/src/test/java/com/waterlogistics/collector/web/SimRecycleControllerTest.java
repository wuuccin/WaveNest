package com.waterlogistics.collector.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.mq.TopicName;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 仿真回收确认端口测试（11-07 弥补 D-16）。
 *
 * <p>判据：端点只发布标准 {@code data.uav.recycle} 消息，不写任何库表。
 * 后半句用反射守住房——端点不得持有任何 Mapper／Repository 字段，
 * 否则「仿真把业务状态提前改掉」的双写点会悄悄回来。
 */
class SimRecycleControllerTest {

    @Test
    @DisplayName("POST 只产生一条标准 MQ 消息，回显主题与四个业务字段")
    void publish_shouldOnlyEmitMqMessage() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimRecycleController controller = new SimRecycleController(publisher);

        ResponseEntity<Map<String, Object>> response = controller.publish(
                "ORD-1", "G-ORD-1-1", "UAV-01", "NEST-01");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals("publishGoodsRecycleConfirmation", body.get("action"));
        assertEquals(TopicName.DATA_UAV_RECYCLE, body.get("topic"));
        assertEquals("ORD-1", body.get("orderId"));
        assertEquals("NEST-01", body.get("nestId"));

        assertEquals(1, publisher.recycles().size());
        GoodsRecycleConfirmation msg = publisher.recycles().get(0);
        assertEquals("ORD-1", msg.orderId());
        assertEquals("G-ORD-1-1", msg.goodsId());
        assertEquals("UAV-01", msg.uavId());
        assertEquals("NEST-01", msg.nestId());
        assertTrue(msg.ts() > 0L);
        assertEquals((Long) body.get("ts"), Long.valueOf(msg.ts()));

        assertTrue(publisher.ais().isEmpty());
        assertTrue(publisher.telemetry().isEmpty());
        assertTrue(publisher.weather().isEmpty());
    }

    @Test
    @DisplayName("端点不持有任何持久化组件字段，物理上无法写库")
    void controller_shouldNotHoldAnyPersistenceComponent() {
        for (Field field : SimRecycleController.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.endsWith("Mapper") || type.endsWith("Repository")
                            || type.endsWith("Service"),
                    "仿真端口不得持有持久化组件：" + field.getName() + " " + type);
        }
    }
}
