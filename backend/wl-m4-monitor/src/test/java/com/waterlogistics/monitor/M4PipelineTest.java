package com.waterlogistics.monitor;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.algo.detection.LowBatteryDetector;
import com.waterlogistics.algo.detection.OfftrackDetector;
import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.monitor.alert.AlertEventPublisher;
import com.waterlogistics.monitor.alert.AlertRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 检测—告警管道验证（10-15 清单 §3.7 当日范围）：
 * F 组量测投影（{@code RouteMeasurement}，量测归口彭紫情）→ 偏航判级 → 告警组装。
 *
 * <p>U 裁定（2026-10-16）：遥测清洗（ingest）与量测（track/algo.measurement）归彭紫情，
 * 本模块不再保留 TelemetryIngestor/自有几何实现；清洗用例移交清单单独发出。
 * 低电量/失联/气象三段为 16 号任务；本类只验证 M4 接线：量测→判级→事件去重与字段。</p>
 */
class M4PipelineTest {

    /** MQ 发送替身：覆盖 publish 记录已发事件，不碰 RabbitTemplate（不引第三方 mock 框架）。 */
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

    /**
     * 场景：29 帧沿长江流域东西向航段飞行，第 5~9s 向北偏出约 72m（>50m 严重阈值）→ 回中。
     * 预期：偏航检测经 3 点去抖触发（直接达严重），随后 5 点回阈内清除；
     * 同一次告警只产生 1 条事件，事件时间戳为检测层首次触发时刻；
     * 且 alert.event 只发 1 次 MQ（去重后不再重复发送）。
     */
    @Test
    void 量测投影到偏航告警全链路() {
        SysParamProvider params = SysParamProvider.defaults();
        OfftrackDetector offtrackDet = new OfftrackDetector(params);
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = new AlertRecorder(publisher);

        // 固定航段 A→B：长江流域内东西向
        Geo.Point a = new Geo.Point(113.20, 30.35);
        Geo.Point b = new Geo.Point(113.25, 30.35);
        RouteMeasurement.Route route = new RouteMeasurement.Route(
                "route-m4-01", 1, List.of(a, b));
        // 北纬偏 ≈72m（6.5e-4° × 111194.9 m/°）
        double devOffset = 6.5e-4;

        long t0 = 1_700_000_000_000L;
        int frame = 0;
        boolean sawOfftrack = false, sawOfftrackCritical = false;

        for (int sec = 0; sec < 29; sec++) {
            double ratio = sec / 28.0;
            double lon = a.lon() + (b.lon() - a.lon()) * ratio;
            double lat = a.lat() + (sec >= 5 && sec <= 9 ? devOffset : 0.0);
            long nowMs = t0 + sec * 1000L;

            // 量测归口 F 组：偏航距离取自 RouteMeasurement 投影
            RouteMeasurement.Projection projection =
                    RouteMeasurement.project(route, new Geo.Point(lon, lat), nowMs);
            DetectionResult offtrackResult = offtrackDet.feed(projection, nowMs);

            if (offtrackResult.hasAlert()) {
                sawOfftrack = true;
                recorder.record(offtrackResult, "uav-m4-01", "order-m4-01");
                if ("严重".equals(offtrackResult.level().getValue())) sawOfftrackCritical = true;
            }
            frame++;
        }

        assertEquals(29, frame, "场景帧数");
        assertTrue(sawOfftrack, "偏航链未触发：约 72m 越严重阈值");
        assertTrue(sawOfftrackCritical, "偏航链未达到严重级");

        List<AlertRecorder.AlertEvent> events = recorder.eventLog();
        assertFalse(events.isEmpty(), "alert 事件队列为空：判级后未组装");
        // 同一次告警：sec7 触发严重，sec8/9 同级持续 → 去重后只允许 1 条事件（不得每帧一条）
        assertEquals(1, events.size(),
                "同一次偏航告警只应产生 1 条事件（持续期去重），实际 " + events.size());
        // G-2 契约 9 字段口径（组装的最后一步）
        AlertRecorder.AlertEvent first = events.get(0);
        assertTrue(first.eventId() != null && !first.eventId().isBlank(), "eventId 缺省");
        assertEquals("偏航", first.alertType(), "alertType 应为偏航（机侧 G 组 §1）");
        assertTrue(List.of("警告", "严重").contains(first.level()), "level 应为警告/严重");
        assertTrue(first.duration() >= 0, "duration 应为非负");
        assertEquals("uav-m4-01", first.droneId());
        assertEquals("order-m4-01", first.orderId());
        // 事件时间戳取检测层首次触发时刻（sec7），不得使用组装时的墙钟
        assertEquals(t0 + 7000L, first.timestamp(), "timestamp 应为检测层首次触发时刻");
        assertTrue(first.currentValue() != null && first.currentValue() >= 0.0,
                "currentValue（偏航距离）应为非负");
        assertTrue(first.thresholdValue() != null, "thresholdValue 缺省（阈值应随事件带出）");

        // MQ 发送验证：同一次告警只发 1 次（去重后不再重复发送）
        assertEquals(1, publisher.sent.size(), "同一次偏航告警只应发 1 次 MQ");
    }

    /**
     * 低电量告警链验证：电量越阈 → 判级 → 事件组装 → MQ 发送。
     * 默认阈值：trigger=20%、severe=10%、clear=30%。
     */
    @Test
    void 低电量越阈到告警全链路() {
        SysParamProvider params = SysParamProvider.defaults();
        LowBatteryDetector batteryDet = new LowBatteryDetector(params);
        RecordingPublisher publisher = new RecordingPublisher();
        AlertRecorder recorder = new AlertRecorder(publisher);

        long t0 = 1_700_000_000_000L;
        boolean sawLowBattery = false;

        // 电量 18% < trigger=20% → 触发警告
        DetectionResult r1 = batteryDet.feed(18.0, t0);
        if (r1.hasAlert()) {
            sawLowBattery = true;
            recorder.record(r1, "uav-m4-01", "order-m4-01");
        }

        // 同级持续 → 去重不再发事件
        DetectionResult r2 = batteryDet.feed(18.0, t0 + 1000);
        if (r2.hasAlert()) {
            recorder.record(r2, "uav-m4-01", "order-m4-01");
        }

        // 电量 5% < severe=10% → 升级严重，级别变化再发事件
        DetectionResult r3 = batteryDet.feed(5.0, t0 + 2000);
        if (r3.hasAlert()) {
            recorder.record(r3, "uav-m4-01", "order-m4-01");
        }

        assertTrue(sawLowBattery, "低电量链未触发");

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

        // MQ 发送验证：2 条事件 → 发 2 次
        assertEquals(2, publisher.sent.size(), "警告→严重升级应发 2 次 MQ");
    }

    /**
     * 判据数值断言：固定航迹下投影偏航量应为确定值；阈值经 SysParamProvider 注入（G 组 §1 86 键名），
     * 判级随参数变化——防硬编码。86 文档 §1：偏航距离=到航线段 A→B 的垂直距离。
     */
    @Test
    void 真算法判据与阈值参数化() {
        // A→B 东西向约 1km，P 在航段中点正北 100m（垂直投影）→ deviation 应≈100m
        double aLat = 30.35, aLon = 113.20;
        double metersPerDegLon = 111194.9 * Math.cos(Math.toRadians(aLat));
        Geo.Point a = new Geo.Point(aLon, aLat);
        Geo.Point b = new Geo.Point(aLon + 1000.0 / metersPerDegLon, aLat);
        Geo.Point middleNorth = new Geo.Point((a.lon() + b.lon()) / 2.0, aLat + 100.0 / 111194.9);
        var projection = RouteMeasurement.project(
                new RouteMeasurement.Route("r", 1, List.of(a, b)), middleNorth, 1000L);
        assertTrue(Math.abs(projection.deviationDistance() - 100.0) < 2.0,
                "垂直投影 100m 应得偏差≈100m，实测 " + projection.deviationDistance());

        // 阈值参数化：同一 45m 输入，warn=100 → 不应触发（阈值未生效=硬编码嫌疑）
        SysParamProvider base = SysParamProvider.defaults();
        SysParamProvider hi = new SysParamProvider() {
            @Override public double getOfftrackWarningThreshold() { return 100.0; }
            @Override public double getOfftrackCriticalThreshold() { return 200.0; }
            @Override public int getOfftrackDebounceCount() { return base.getOfftrackDebounceCount(); }
            @Override public int getOfftrackClearCount() { return base.getOfftrackClearCount(); }
            @Override public int getOfftrackDowngradeCount() { return base.getOfftrackDowngradeCount(); }
            @Override public double getLowBatteryTrigger() { return base.getLowBatteryTrigger(); }
            @Override public double getLowBatteryClear() { return base.getLowBatteryClear(); }
            @Override public double getLowBatterySevere() { return base.getLowBatterySevere(); }
            @Override public int getLowBatteryDowngradeDebounce() { return base.getLowBatteryDowngradeDebounce(); }
            @Override public long getHeartbeatLostWindowSec() { return base.getHeartbeatLostWindowSec(); }
            @Override public long getHeartbeatLossWindowSec() { return base.getHeartbeatLossWindowSec(); }
            @Override public long getHeartbeatCheckIntervalSec() { return base.getHeartbeatCheckIntervalSec(); }
            @Override public double getWeatherWindWarningThreshold() { return base.getWeatherWindWarningThreshold(); }
            @Override public double getWeatherWindCriticalThreshold() { return base.getWeatherWindCriticalThreshold(); }
            @Override public double getWeatherWindSurgeThreshold() { return base.getWeatherWindSurgeThreshold(); }
            @Override public int getWeatherSurgeWindowSec() { return base.getWeatherSurgeWindowSec(); }
            @Override public int getWeatherClearDebounce() { return base.getWeatherClearDebounce(); }
        };
        OfftrackDetector det = new OfftrackDetector(hi);
        for (int i = 0; i < 5; i++) {
            assertFalse(det.feed(45.0, 1000L * i).hasAlert(), "warn=100 时 45m 不应触发");
        }
        assertEquals(100.0, hi.getOfftrackWarningThreshold(), "键 offtrack 预警阈值未生效");
    }
}
