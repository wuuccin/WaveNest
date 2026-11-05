package com.waterlogistics.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.RendezvousFrame;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T1-1（10-27 契约与地基）：三个实时帧与告警消息对象的<b>扩字段可读性</b>。
 *
 * <p>判据来自任务清单：三个帧的 record 上能读到全部新字段；
 * 兼容构造器保留旧签名且新字段默认空（取不到就留空，不许造假坐标）。
 *
 * <p>本测试只钉「字段在位且类型正确、工厂默认空」，<b>不钉字段总数</b>——
 * 契约字段可随后续轮次继续演进，逐字段断言比计数断言更贴近判据本意。
 */
class WsFrameExtendedFieldTest {

    @Test
    void alarmFrame_shouldCarryPositionOrderAndEventId() {
        AlarmFrame frame = new AlarmFrame(AlertType.LOW_BATTERY, AlertLevel.WARN,
                "UAV-01", "电量低于阈值", 115.91, 29.72, "ORD-1", "EV-1");

        assertEquals(115.91, frame.lon());
        assertEquals(29.72, frame.lat());
        assertEquals("ORD-1", frame.orderId());
        assertEquals("EV-1", frame.eventId());
    }

    @Test
    void alarmFrame_ofLegacySignature_shouldLeaveNewFieldsNull() {
        AlarmFrame frame = new AlarmFrame(AlertType.LOW_BATTERY, AlertLevel.WARN, "UAV-01", "电量低于阈值");

        assertNull(frame.lon());
        assertNull(frame.lat());
        assertNull(frame.orderId());
        assertNull(frame.eventId());
        assertEquals(AlertType.LOW_BATTERY, frame.alertType());
        assertEquals("UAV-01", frame.target());
    }

    @Test
    void droneStateFrame_shouldCarryHeadingAndActiveOrderId() {
        DroneStateFrame frame = new DroneStateFrame("UAV-01", 115.91, 29.72,
                83.0, "去程中", 1_700_000_000_000L, 127.5, "ORD-1");

        assertEquals(127.5, frame.heading());
        assertEquals("ORD-1", frame.activeOrderId());
    }

    @Test
    void droneStateFrame_ofLegacySignature_shouldLeaveNewFieldsNull() {
        DroneStateFrame frame = new DroneStateFrame("UAV-01", 115.91, 29.72,
                83.0, null, 1_700_000_000_000L);

        assertNull(frame.heading());
        assertNull(frame.activeOrderId());
        assertEquals("UAV-01", frame.uavId());
        assertEquals(83.0, frame.battery());
    }

    @Test
    void rendezvousFrame_shouldCarryCandidateCountReasonsAndEta() {
        RendezvousFrame frame = new RendezvousFrame("ORD-1", 115.9401, 29.7302,
                true, 3, List.of("UAV-02：电量不足", "UAV-03：超出服务半径"), 1_700_000_000_000L);

        assertEquals(3, frame.candidateCount());
        assertEquals(List.of("UAV-02：电量不足", "UAV-03：超出服务半径"), frame.rejectionReasons());
        assertEquals(1_700_000_000_000L, frame.etaTs());
    }

    @Test
    void rendezvousFrame_ofLegacySignature_shouldLeaveNewFieldsNull() {
        RendezvousFrame frame = new RendezvousFrame("ORD-1", 115.9401, 29.7302, true);

        assertNull(frame.candidateCount());
        assertNull(frame.rejectionReasons());
        assertNull(frame.etaTs());
        assertEquals("ORD-1", frame.orderId());
        assertTrue(frame.locked());
    }

    @Test
    void alertEventMessage_shouldCarryPosition() {
        AlertEventMessage event = new AlertEventMessage(
                "EV-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0, 115.91, 29.72);

        assertEquals(115.91, event.lon());
        assertEquals(29.72, event.lat());
    }

    @Test
    void alertEventMessage_ofLegacySignature_shouldLeavePositionNull() {
        AlertEventMessage event = new AlertEventMessage(
                "EV-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0);

        assertNull(event.lon());
        assertNull(event.lat());
        assertEquals("EV-1", event.eventId());
        assertEquals("ORD-1", event.orderId());
    }

    /**
     * 兼容构造器的旧参数顺序与 T1-1 之前的字段顺序逐项一致（迁移时可机械替换）。
     */
    @Test
    void legacyConstructors_shouldMatchOldComponentOrder() {
        assertEquals(List.of("alertType", "alertLevel", "target", "message"),
                componentNames(AlarmFrame.class, 4));
        assertEquals(List.of("uavId", "lon", "lat", "battery", "phase", "ts"),
                componentNames(DroneStateFrame.class, 6));
        assertEquals(List.of("orderId", "meetLon", "meetLat", "locked"),
                componentNames(RendezvousFrame.class, 4));
        assertEquals(List.of("eventId", "alertType", "level", "duration",
                        "droneId", "orderId", "timestamp", "currentValue", "thresholdValue"),
                componentNames(AlertEventMessage.class, 9));
    }

    private static List<String> componentNames(Class<?> record, int limit) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .limit(limit)
                .toList();
    }
}
