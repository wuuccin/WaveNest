package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.monitor.alert.AlertEventPublisher;
import com.waterlogistics.monitor.alert.AlertRecorder;
import com.waterlogistics.monitor.detect.DetectionOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C5 集成验证：遥测帧 → 清洗 → 检测 → 告警记录 → MQ 发送。
 *
 * <p>不依赖真实 RabbitMQ：直接调用 {@link UavTelemetryListener#onTelemetryMessage}，
 * 验证 M4 检测链在真实链路上被驱动。偏航样本因 projection 未接入（待彭紫情 track 侧）
 * 暂不验证；低电量样本验证完整链路。</p>
 */
class UavTelemetryListenerTest {

    /** MQ 发送替身：覆盖 publish 记录已发事件，不碰 RabbitTemplate。 */
    private static final class RecordingPublisher extends AlertEventPublisher {
        final List<AlertRecorder.AlertEvent> sent = new ArrayList<>();
        RecordingPublisher() { super(null); }
        @Override public void publish(AlertRecorder.AlertEvent event) { sent.add(event); }
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
        UavTelemetryListener listener = new UavTelemetryListener(telemetryService, orchestrator);

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
        List<AlertRecorder.AlertEvent> events = recorder.eventLog();
        assertEquals(2, events.size(), "警告→严重升级应产生 2 条事件");

        AlertRecorder.AlertEvent warnEvent = events.get(0);
        assertEquals("低电量", warnEvent.alertType());
        assertEquals("警告", warnEvent.level());
        assertEquals(t0, warnEvent.timestamp());
        assertEquals(18.0, warnEvent.currentValue());
        assertEquals(20.0, warnEvent.thresholdValue());

        AlertRecorder.AlertEvent critEvent = events.get(1);
        assertEquals("低电量", critEvent.alertType());
        assertEquals("严重", critEvent.level());
        assertEquals(t0, critEvent.timestamp(), "升级不重置首次触发时刻");
        assertEquals(5.0, critEvent.currentValue());
        assertEquals(10.0, critEvent.thresholdValue());

        // 验证：MQ 发送
        assertEquals(2, publisher.sent.size(), "警告→严重升级应发 2 次 MQ");
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
        UavTelemetryListener listener = new UavTelemetryListener(telemetryService, orchestrator);

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
        UavTelemetryListener listener = new UavTelemetryListener(telemetryService, orchestrator);

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
}
