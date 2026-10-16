package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.monitor.alert.AlertEventPublisher;
import com.waterlogistics.monitor.alert.AlertRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检测编排端到端验证（10-16 §3.7：DetectionSink 接线）。
 *
 * <p>不起 Spring、不连 MQ/DB：AlertRecorder 走单参构造（alertMapper=null），
 * publisher 用子类替身留痕；失联时间用包级 {@code sweep(nowMs)} 注入假时钟推进。
 * 覆盖：心跳 sweep→失联 RAISED→灭失 LOSS_DECLARED（同级补发、eventId 复用）
 * →遥测恢复 resolve→再失联新 eventId；低电/偏航经编排器投递与 CLEARED 复位；
 * 任务结束清活跃表（下一单重新起事件）；地面机不误报；多机隔离。</p>
 */
class DetectionOrchestratorTest {

    /** MQ 发送替身：覆盖 publish 留痕，不碰 RabbitTemplate（不引第三方 mock 框架）。 */
    private static final class RecordingPublisher extends AlertEventPublisher {
        final List<AlertRecorder.AlertEvent> sent = new ArrayList<>();
        RecordingPublisher() {
            super(null);
        }
        @Override
        public void publish(AlertRecorder.AlertEvent event) {
            sent.add(event);
        }
    }

    private static DetectionOrchestrator newChain(RecordingPublisher publisher) {
        AlertRecorder recorder = new AlertRecorder(publisher);
        return new DetectionOrchestrator(recorder, SysParamProvider.defaults());
    }

    private static DetectionSample frame(String droneId, String orderId,
                                         Double battery, RouteMeasurement.Projection projection,
                                         long tsMs, boolean missionActive) {
        return new DetectionSample(droneId, orderId, battery, projection, tsMs, missionActive);
    }

    @Test
    void 失联到灭失_编排补发且复用eventId() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        // t=0 首帧（在飞、电量正常、无航迹投影）→ 建立心跳
        sink.onSample(frame("uav-hb", "order-hb", 80.0, null, 0L, true));

        // 31s 无心跳 sweep → 失联 RAISED（严重）
        sink.sweep(31_000);
        // 60s sweep → 持续帧去重，无新事件
        sink.sweep(60_000);
        // 121s sweep → 灭失推定 LOSS_DECLARED，同级强制补发一次
        sink.sweep(121_000);
        // 灭失后再 sweep 仍是持续帧，去重
        sink.sweep(130_000);

        assertEquals(2, pub.sent.size(), "失联首发 + 灭失补发应共 2 条");
        AlertRecorder.AlertEvent lost = pub.sent.get(0);
        AlertRecorder.AlertEvent loss = pub.sent.get(1);
        assertEquals("失联", lost.alertType());
        assertEquals("严重", lost.level());
        assertEquals("失联", loss.alertType());
        assertEquals("严重", loss.level(), "方案 B：灭失与失联同级，不升级");
        assertEquals(lost.eventId(), loss.eventId(), "灭失补发复用失联 eventId");
        assertEquals(lost.timestamp(), loss.timestamp(), "补发沿用失联首次触发时刻");
    }

    @Test
    void 灭失前遥测恢复_清除活跃失联_再失联以新eventId起事件() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        sink.onSample(frame("uav-rec", "order-rec", 80.0, null, 0L, true));
        sink.sweep(31_000);   // 失联 RAISED
        sink.sweep(60_000);   // 持续，无新事件
        assertEquals(1, pub.sent.size());
        String firstId = pub.sent.get(0).eventId();

        // 90s 遥测到达（已失联、未灭失）→ RECOVERED → 编排器 resolve 活跃失联
        sink.onSample(frame("uav-rec", "order-rec", 80.0, null, 90_000, true));
        // 100s sweep：距最近心跳 10s，不应再报
        sink.sweep(100_000);
        assertEquals(1, pub.sent.size(), "恢复后短期内不得再报失联");

        // 125s（距恢复心跳 35s）仍无新心跳 → 再次失联，必须以新 eventId 起事件
        sink.sweep(125_000);
        assertEquals(2, pub.sent.size(), "resolve 后再次失联应产生新事件");
        assertNotEquals(firstId, pub.sent.get(1).eventId(),
                "恢复复位后的新告警周期必须换新 eventId");
    }

    @Test
    void 低电量经编排器触发升级并在清除后重新起事件() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        sink.onSample(frame("uav-bat", "order-bat", 15.0, null, 1_000, true));  // 警告
        sink.onSample(frame("uav-bat", "order-bat", 15.0, null, 2_000, true));  // 同级去重
        sink.onSample(frame("uav-bat", "order-bat", 5.0, null, 3_000, true));   // 升级严重
        sink.onSample(frame("uav-bat", "order-bat", 35.0, null, 4_000, true));  // CLEARED→resolve
        sink.onSample(frame("uav-bat", "order-bat", 15.0, null, 5_000, true));  // 重新越阈→新事件

        assertEquals(3, pub.sent.size(), "警告、升级、复位后再触发各 1 条");
        assertEquals("低电量", pub.sent.get(0).alertType());
        assertEquals("警告", pub.sent.get(0).level());
        assertEquals("严重", pub.sent.get(1).level());
        assertEquals(pub.sent.get(0).eventId(), pub.sent.get(1).eventId(), "升级复用 eventId");
        assertNotEquals(pub.sent.get(0).eventId(), pub.sent.get(2).eventId(),
                "CLEARED 复位后再触发必须换新 eventId");
    }

    @Test
    void 偏航取自F组投影_触发与清除复位() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        Geo.Point a = new Geo.Point(113.20, 30.35);
        Geo.Point b = new Geo.Point(113.25, 30.35);
        RouteMeasurement.Route route = new RouteMeasurement.Route("route-e2e", 1, List.of(a, b));
        double midLon = (a.lon() + b.lon()) / 2.0;
        double devOffset = 6.5e-4; // ≈72m，越严重阈值 50m

        // 连续 5 帧偏出（去抖 3 点后触发）
        for (int i = 0; i < 5; i++) {
            var p = RouteMeasurement.project(route, new Geo.Point(midLon, a.lat() + devOffset), 1_000 * i);
            sink.onSample(frame("uav-off", "order-off", null, p, 1_000 * i, true));
        }
        assertTrue(pub.sent.size() >= 1, "连续越限应触发偏航告警");
        AlertRecorder.AlertEvent first = pub.sent.get(0);
        assertEquals("偏航", first.alertType());
        assertTrue(List.of("警告", "严重").contains(first.level()));

        // 连续 6 帧回到航线上（清除去抖 5 点）→ CLEARED 只 resolve，不产事件
        int beforeRecover = pub.sent.size();
        for (int i = 5; i < 11; i++) {
            var p = RouteMeasurement.project(route, new Geo.Point(midLon, a.lat()), 1_000 * i);
            sink.onSample(frame("uav-off", "order-off", null, p, 1_000 * i, true));
        }
        assertEquals(beforeRecover, pub.sent.size(), "回阈内帧不得发告警（CLEARED 只复位）");

        // 再次偏出 → 新周期、新 eventId（证明 CLEARED 已 resolve 活跃表）
        for (int i = 11; i < 16; i++) {
            var p = RouteMeasurement.project(route, new Geo.Point(midLon, a.lat() + devOffset), 1_000 * i);
            sink.onSample(frame("uav-off", "order-off", null, p, 1_000 * i, true));
        }
        assertTrue(pub.sent.size() >= 2, "复位后再次偏出应重新起事件");
        assertNotEquals(first.eventId(), pub.sent.get(pub.sent.size() - 1).eventId(),
                "清除后再触发必须换新 eventId");
    }

    @Test
    void 任务结束清活跃告警_下一单同型告警不被吞() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        // 本单：低电严重
        sink.onSample(frame("uav-mission", "order-1", 5.0, null, 1_000, true));
        assertEquals(1, pub.sent.size());
        String firstId = pub.sent.get(0).eventId();

        // 任务结束：检测器与活跃去重表一并复位
        sink.onMissionEnd("uav-mission");

        // 下一单同型告警首帧必须能发出（不被上一单残留状态去重）
        sink.onSample(frame("uav-mission", "order-2", 5.0, null, 2_000, true));
        assertEquals(2, pub.sent.size(), "下一单首帧同型告警不得被去重吞掉");
        assertNotEquals(firstId, pub.sent.get(1).eventId(), "下一单告警必须是新事件");
        assertEquals("order-2", pub.sent.get(1).orderId());

        // 未登记的无人机结束任务应安全无操作
        sink.onMissionEnd("uav-never-seen");
    }

    @Test
    void 地面机不误报失联且多机互不串扰() {
        RecordingPublisher pub = new RecordingPublisher();
        DetectionOrchestrator sink = newChain(pub);

        // 地面机（missionActive=false）即便 121s 无心跳也不报失联
        sink.onSample(frame("uav-ground", null, 80.0, null, 0L, false));
        sink.sweep(121_000);
        assertTrue(pub.sent.stream().noneMatch(e -> "失联".equals(e.alertType())),
                "地面机不得报失联");

        // A 机低电触发、B 机电量正常：事件只能挂 A 机
        sink.onSample(frame("uav-A", "order-A", 5.0, null, 1_000, true));
        sink.onSample(frame("uav-B", "order-B", 80.0, null, 1_000, true));
        long aAlerts = pub.sent.stream().filter(e -> "uav-A".equals(e.droneId())).count();
        long bAlerts = pub.sent.stream().filter(e -> "uav-B".equals(e.droneId())).count();
        assertEquals(1, aAlerts);
        assertEquals(0, bAlerts, "正常无人机不得产生告警");
    }
}
