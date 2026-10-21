package com.waterlogistics.monitor.alert;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.algo.detection.HeartbeatLostDetector;
import com.waterlogistics.algo.detection.LowBatteryDetector;
import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.monitor.alert.AlertRecorder.AlertEvent;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * AlertRecorder 去重与事件身份验证（同一告警 eventId 贯穿；G-01/G-4/G-12；
 * 失联级别 2026-10-16 裁决方案 B：失联＝灭失＝严重，灭失凭 LOSS_DECLARED 相位强制补发）。
 *
 * <p>测试替身只用 JDK：发送器子类覆盖 publish 留痕；AlertMapper 是接口，
 * 用 JDK 动态代理记录 insert/update/deleteById 调用，不引第三方 mock 框架。</p>
 */
class AlertRecorderTest {

    /** MQ 发送替身：覆盖 publish，记录已发事件，不碰 RabbitTemplate。 */
    private static final class RecordingPublisher extends AlertEventPublisher {
        final List<AlertEvent> sent = new ArrayList<>();
        RecordingPublisher() {
            super(null);
        }
        @Override
        public void publish(AlertEvent event) {
            sent.add(event);
        }
    }

    /** alert 表 Mapper 替身：只记录 insert/update/deleteById 三类调用次数与入参。 */
    private static final class MapperRecorder {
        final List<AlertEntity> inserts = new ArrayList<>();
        int updates;
        int deleteByIds;

        AlertMapper proxy() {
            return (AlertMapper) Proxy.newProxyInstance(
                    AlertMapper.class.getClassLoader(),
                    new Class<?>[]{AlertMapper.class},
                    (p, method, args) -> {
                        switch (method.getName()) {
                            case "insert" -> {
                                inserts.add((AlertEntity) args[0]);
                                return 1;
                            }
                            case "update" -> {
                                updates++;
                                return 1;
                            }
                            case "deleteById" -> {
                                deleteByIds++;
                                return 1;
                            }
                            default -> {
                                if (method.getReturnType() == boolean.class) return false;
                                if (method.getReturnType().isPrimitive()) return 0;
                                return null;
                            }
                        }
                    });
        }
    }

    private AlertRecorder newRecorder(AlertEventPublisher publisher) {
        return new AlertRecorder(publisher);
    }

    private SysParamProvider params() {
        return SysParamProvider.defaults();
    }

    @Test
    void 级别变化复用同一eventId同级持续去重() {
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = newRecorder(publisher);
        LowBatteryDetector det = new LowBatteryDetector(params());

        DetectionResult warn = det.feed(15.0, 1_000);   // RAISED 警告
        DetectionResult crit = det.feed(5.0, 2_000);   // LEVEL_CHANGED 严重
        DetectionResult hold = det.feed(5.0, 3_000);   // DURATION 严重持续

        AlertEvent e1 = recorder.record(warn, "uav-1", "order-9");
        AlertEvent e2 = recorder.record(crit, "uav-1", "order-9");
        AlertEvent dropped = recorder.record(hold, "uav-1", "order-9");

        assertNotNull(e1);
        assertNotNull(e2, "级别变化必须产事件");
        assertNull(dropped, "同级持续帧去重，不产事件");
        assertEquals(e1.eventId(), e2.eventId(), "同一告警一个 eventId 贯穿");

        // 字段契约：中文取值、首发时间戳不随级别变化漂移
        assertEquals("低电量", e1.alertType());
        assertEquals("警告", e1.level());
        assertEquals("严重", e2.level());
        assertEquals(e1.timestamp(), e2.timestamp(), "级别变化沿用首次触发时刻");
        assertEquals("uav-1", e1.droneId());
        assertEquals("order-9", e1.orderId());
        assertEquals(15.0, e1.currentValue());
        assertEquals(20.0, e1.thresholdValue());
        assertEquals(10.0, e2.thresholdValue());

        // 2 条事件 → 发 2 次 MQ
        assertEquals(2, publisher.sent.size());
    }

    @Test
    void 灭失推定同级强制补发一次且复用失联eventId() {
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = newRecorder(publisher);
        HeartbeatLostDetector hb = new HeartbeatLostDetector(params());
        hb.heartbeat(0);

        DetectionResult raised = hb.check(31_000, true);    // RAISED 严重（失联，方案 B）
        DetectionResult during = hb.check(60_000, true);    // DURATION
        DetectionResult loss = hb.check(121_000, true);     // LOSS_DECLARED 严重（灭失，同级凭相位标识）
        DetectionResult after = hb.check(130_000, true);    // DURATION

        AlertEvent e1 = recorder.record(raised, "uav-2", null);
        AlertEvent dropped = recorder.record(during, "uav-2", null);
        AlertEvent e2 = recorder.record(loss, "uav-2", null);
        AlertEvent droppedAfter = recorder.record(after, "uav-2", null);

        assertNotNull(e1);
        assertNull(dropped);
        assertNotNull(e2, "灭失推定凭 LOSS_DECLARED 相位强制补发一次（方案 B）");
        assertNull(droppedAfter, "灭失之后的持续帧恢复去重");
        assertEquals(e1.eventId(), e2.eventId(), "灭失补发不换新 id（同一 event_id）");
        assertEquals("失联", e1.alertType());
        assertEquals("严重", e1.level(), "失联档＝严重（方案 B）");
        assertEquals("失联", e2.alertType());
        assertEquals("严重", e2.level(), "灭失档＝严重（与失联同级，不升级）");
        assertEquals(e1.timestamp(), e2.timestamp(), "灭失补发沿用失联首次触发时刻");
        assertNull(e2.currentValue(), "失联事件 currentValue 为 null");
        assertEquals(120.0, e2.thresholdValue(), "灭失档阈值＝灭失窗");

        // 2 条事件 → 发 2 次 MQ
        assertEquals(2, publisher.sent.size());
    }

    @Test
    void resolve复位后同型告警以新eventId重启事件() {
        AlertRecorder recorder = newRecorder(new RecordingPublisher());
        LowBatteryDetector det = new LowBatteryDetector(params());

        AlertEvent first = recorder.record(det.feed(15.0, 1_000), "uav-3", null);
        // 检测层清除相位本身不入流水
        DetectionResult cleared = det.feed(35.0, 2_000);
        assertNull(recorder.record(cleared, "uav-3", null));
        // 接线层据 CLEARED 调 resolve
        assertSame(DetectionResult.Phase.CLEARED, cleared.phase());
        recorder.resolve("uav-3", AlertType.LOW_BATTERY);

        AlertEvent second = recorder.record(det.feed(15.0, 3_000), "uav-3", null);
        assertNotNull(second);
        assertNotEquals(first.eventId(), second.eventId(), "复位后再触发必须是新事件");

        // 流水顺序与类型隔离：不同无人机互不影响去重
        LowBatteryDetector other = new LowBatteryDetector(params());
        AlertEvent otherEvent = recorder.record(other.feed(15.0, 1_000), "uav-4", null);
        assertNotNull(otherEvent);
        assertNotEquals(second.eventId(), otherEvent.eventId());

        List<AlertRecorder.AlertEvent> log = recorder.eventLog();
        assertEquals(3, log.size());
    }

    @Test
    void 无告警结果一律不产事件() {
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = newRecorder(publisher);
        assertNull(recorder.record(DetectionResult.none(AlertType.DEVIATION), "uav-5", null));
        assertNull(recorder.record(DetectionResult.cleared(AlertType.DEVIATION), "uav-5", null));
        assertEquals(0, recorder.eventLog().size());
        assertEquals(0, publisher.sent.size(), "无事件不得发 MQ");
    }

    @Test
    void 首次触发落库insert级别变化update且仅八列口径() {
        MapperRecorder mr = new MapperRecorder();
        AlertRecorder recorder = new AlertRecorder(new RecordingPublisher(), mr.proxy());
        LowBatteryDetector det = new LowBatteryDetector(params());

        // RAISED 警告 → insert 一行：alertId=eventId、中文枚举、trigger_time 非空、未处理
        AlertEvent warn = recorder.record(det.feed(15.0, 1_000), "uav-6", "order-6");
        assertEquals(1, mr.inserts.size(), "首次触发必须 insert 一次");
        AlertEntity row = mr.inserts.get(0);
        assertEquals(warn.eventId(), row.getAlertId(), "alert_id 复用 eventId");
        assertEquals("uav-6", row.getDroneId());
        assertEquals("order-6", row.getOrderId());
        assertEquals("低电量", row.getAlertType());
        assertEquals("警告", row.getLevel());
        assertEquals("未处理", row.getHandleStatus());
        assertNotNull(row.getTriggerTime());
        assertNull(row.getHandleRecord(), "处置记录落库时为空");

        // 升级严重 → update level（同一 alertId），不得再 insert
        AlertEvent crit = recorder.record(det.feed(5.0, 2_000), "uav-6", "order-6");
        assertEquals(warn.eventId(), crit.eventId());
        assertEquals(1, mr.inserts.size(), "级别变化不得再 insert");
        assertEquals(1, mr.updates, "级别变化必须 update level 一次");

        // 灭失同级补发不更新 level（无级别变化）
        HeartbeatLostDetector hb = new HeartbeatLostDetector(params());
        hb.heartbeat(0);
        recorder.record(hb.check(31_000, true), "uav-6", "order-6");
        recorder.record(hb.check(121_000, true), "uav-6", "order-6");
        assertEquals(2, mr.inserts.size(), "失联首次触发另起一行 insert");
        assertEquals(1, mr.updates, "灭失与失联同级，不 update");

        // 清除复位不碰库（handle_status 无「已清除」态，不臆造）
        recorder.resolve("uav-6", AlertType.LOW_BATTERY);
        assertEquals(0, mr.deleteByIds, "复位不得 delete");
    }
}
