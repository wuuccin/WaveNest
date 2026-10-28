package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.monitor.alert.AlertEventPublisher;
import com.waterlogistics.monitor.alert.AlertRecorder;
import com.waterlogistics.monitor.detect.DetectionOrchestrator;
import com.waterlogistics.monitor.query.FlightQueryService;
import com.waterlogistics.monitor.store.FlightRecordStore;
import com.waterlogistics.monitor.track.FlightContextProvider;
import com.waterlogistics.monitor.track.FlightPointSink;
import com.waterlogistics.monitor.track.FlightTracker;
import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C5 集成验证：遥测帧 → 清洗 → 检测 → 告警记录 → MQ 发送。
 *
 * <p>不依赖真实 RabbitMQ：直接调用 {@link UavTelemetryListener#onTelemetryMessage}，
 * 验证 M4 检测链在真实链路上被驱动，包括 T2-3 直线基准偏航投影与 T2-9
 * 清洗后航迹点持久化边界。</p>
 */
class UavTelemetryListenerTest {

    /** MQ 发送替身：覆盖 publish 记录已发事件，不碰 RabbitTemplate。 */
    private static final class RecordingPublisher extends AlertEventPublisher {
        final List<AlertEventMessage> sent = new ArrayList<>();
        RecordingPublisher() { super(null); }
        @Override public void publish(AlertEventMessage event) { sent.add(event); }
    }

    @Test
    void 低电量遥测帧驱动检测链并产告警() {
        // 准备
        SysParamProvider params = SysParamProvider.defaults();
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = new AlertRecorder(publisher);
        DetectionOrchestrator orchestrator = new DetectionOrchestrator(recorder, params);
        TelemetryService telemetryService = new TelemetryService(
                new com.waterlogistics.algo.measurement.TelemetryCleaner.Limits(
                        50, 5000, 15, 20, 30, 1000), 4.5);
        FlightRecordStore store = new FlightRecordStore();
        UavTelemetryListener listener = new UavTelemetryListener(telemetryService, orchestrator, store);

        long t0 = 1_700_000_000_000L;

        // 注入低电量帧（18% < trigger=20% → 应触发警告）
        UavTelemetry lowBatteryFrame = new UavTelemetry(
                "uav-c5-01", 113.20, 30.35, 100, 10, 18.0, 90, t0);
        listener.onTelemetryMessage(lowBatteryFrame);

        // 同级持续帧 → 去重不再发事件
        UavTelemetry sameFrame = new UavTelemetry(
                "uav-c5-01", 113.20, 30.35, 100, 10, 18.0, 90, t0 + 1000);
        listener.onTelemetryMessage(sameFrame);

        // 严重电量帧（5% < severe=10% → 升级严重）
        UavTelemetry criticalFrame = new UavTelemetry(
                "uav-c5-01", 113.20, 30.35, 100, 10, 5.0, 90, t0 + 2000);
        listener.onTelemetryMessage(criticalFrame);

        // 验证：告警记录
        List<AlertEventMessage> events = recorder.eventLog();
        assertEquals(2, events.size(), "警告→严重升级应产生 2 条事件");

        AlertEventMessage warnEvent = events.get(0);
        assertEquals("低电量", warnEvent.alertType());
        assertEquals("警告", warnEvent.level());
        assertEquals(t0, warnEvent.timestamp());
        assertEquals(18.0, warnEvent.currentValue());
        assertEquals(20.0, warnEvent.thresholdValue());

        AlertEventMessage critEvent = events.get(1);
        assertEquals("低电量", critEvent.alertType());
        assertEquals("严重", critEvent.level());
        assertEquals(t0, critEvent.timestamp(), "升级不重置首次触发时刻");
        assertEquals(5.0, critEvent.currentValue());
        assertEquals(10.0, critEvent.thresholdValue());

        // 验证：MQ 发送
        assertEquals(2, publisher.sent.size(), "警告→严重升级应发 2 次 MQ");

        // D0：三条记录均为清洗后的 TelemetryState，并按 uavId 形成可查询飞行任务。
        FlightQueryService query = new FlightQueryService(store);
        assertEquals(List.of("uav-c5-01"), query.tasks());
        assertEquals(3, query.replay("uav-c5-01").size());
    }

    @Test
    void 正常电量遥测帧不产告警() {
        SysParamProvider params = SysParamProvider.defaults();
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = new AlertRecorder(publisher);
        DetectionOrchestrator orchestrator = new DetectionOrchestrator(recorder, params);
        TelemetryService telemetryService = new TelemetryService(
                new com.waterlogistics.algo.measurement.TelemetryCleaner.Limits(
                        50, 5000, 15, 20, 30, 1000), 4.5);
        UavTelemetryListener listener = new UavTelemetryListener(
                telemetryService, orchestrator, new FlightRecordStore());

        long t0 = 1_700_000_000_000L;

        // 注入正常电量帧（80% > trigger=20%）
        UavTelemetry normalFrame = new UavTelemetry(
                "uav-c5-02", 113.20, 30.35, 100, 10, 80.0, 90, t0);
        listener.onTelemetryMessage(normalFrame);

        assertTrue(recorder.eventLog().isEmpty(), "正常电量不应产生告警");
        assertTrue(publisher.sent.isEmpty(), "正常电量不应发 MQ");
    }

    @Test
    void 非法遥测帧被丢弃不中断后续() {
        SysParamProvider params = SysParamProvider.defaults();
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = new AlertRecorder(publisher);
        DetectionOrchestrator orchestrator = new DetectionOrchestrator(recorder, params);
        TelemetryService telemetryService = new TelemetryService(
                new com.waterlogistics.algo.measurement.TelemetryCleaner.Limits(
                        50, 5000, 15, 20, 30, 1000), 4.5);
        UavTelemetryListener listener = new UavTelemetryListener(
                telemetryService, orchestrator, new FlightRecordStore());

        // 空消息
        listener.onTelemetryMessage(null);
        // 缺 uavId
        listener.onTelemetryMessage(new UavTelemetry(null, 113.20, 30.35, 100, 10, 80.0, 90, 1000L));

        // 后续正常帧仍被处理
        UavTelemetry normalFrame = new UavTelemetry(
                "uav-c5-03", 113.20, 30.35, 100, 10, 15.0, 90, 2000L);
        listener.onTelemetryMessage(normalFrame);

        // 15% < trigger=20% → 应触发警告
        assertEquals(1, recorder.eventLog().size(), "后续低电量帧应被正常处理");
    }

    @Test
    void 直线基准投影接入偏航检测且航迹点带订单上下文() {
        SysParamProvider params = SysParamProvider.defaults();
        RecordingPublisher publisher = new RecordingPublisher();
        FlightRecordStore store = new FlightRecordStore();
        AlertRecorder recorder = new AlertRecorder(publisher, null, store);
        DetectionOrchestrator orchestrator = new DetectionOrchestrator(recorder, params);
        TelemetryService telemetryService = new TelemetryService(
                new com.waterlogistics.algo.measurement.TelemetryCleaner.Limits(
                        50, 5000, 15, 20, 30, 1000), 4.5);

        RouteMeasurement.Route route = new RouteMeasurement.Route(
                "straight:ORD-T2-3", 1L,
                List.of(new Geo.Point(115.8600, 28.6400),
                        new Geo.Point(115.8600, 28.6900)));
        RouteMeasurement.Landing landing = new RouteMeasurement.Landing(
                "会面点", "ORD-T2-3", new Geo.Point(115.8600, 28.6900), 1L, 1);
        FlightContextProvider.FlightContext context = new FlightContextProvider.FlightContext(
                "ORD-T2-3", "去程", route, landing);
        FlightContextProvider contextProvider = droneId -> context;
        List<FlightContextProvider.FlightContext> persistedContexts = new ArrayList<>();
        FlightPointSink sink = (state, resolved) -> persistedContexts.add(resolved);
        UavTelemetryListener listener = new UavTelemetryListener(
                telemetryService, orchestrator, store,
                contextProvider, new FlightTracker(), sink);

        long t0 = 1_700_000_000_000L;
        // 约向东偏 98m，连续三帧越过严重阈值 50m，满足默认去抖 3 点。
        for (int i = 0; i < 3; i++) {
            listener.onTelemetryMessage(new UavTelemetry(
                    "UAV-T2-3", 115.8610, 28.6650, 100, 10, 80.0, 0, t0 + i * 1000));
        }

        assertEquals(3, persistedContexts.size(), "每帧清洗后状态都应进入航迹落库边界");
        assertTrue(persistedContexts.stream().allMatch(value ->
                "ORD-T2-3".equals(value.orderId()) && "去程".equals(value.segmentType())));
        assertEquals(1, recorder.eventLog().size(), "偏航越严重阈值并满足去抖后应产生一条告警");
        AlertEventMessage event = recorder.eventLog().get(0);
        assertEquals("偏航", event.alertType());
        assertEquals("ORD-T2-3", event.orderId(), "偏航告警应带当前派发订单号");
        assertEquals("UAV-T2-3", event.droneId());
    }
}
