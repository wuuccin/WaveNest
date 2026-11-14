package com.waterlogistics.monitor.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.storage.param.SysParamLoader;
import org.junit.jupiter.api.Test;

class WeatherViolationDetectorTest {

    static SysParamProvider parameters() {
        SysParamLoader loader = new SysParamLoader(null) {
            @Override
            public double getDouble(String key, double defaultValue) {
                return switch (key) {
                    case SysParamBridge.WEATHER_WIND_WARNING_THRESHOLD -> 8.0;
                    case SysParamBridge.WEATHER_WIND_CRITICAL_THRESHOLD -> 14.0;
                    case SysParamBridge.WEATHER_WIND_SURGE_THRESHOLD -> 0.5;
                    default -> defaultValue;
                };
            }

            @Override
            public int getInt(String key, int defaultValue) {
                return switch (key) {
                    case SysParamBridge.WEATHER_SURGE_WINDOW_SEC -> 10;
                    case SysParamBridge.WEATHER_CLEAR_DEBOUNCE -> 5;
                    default -> defaultValue;
                };
            }
        };
        return new SysParamBridge(loader);
    }

    @Test
    void 双阈值分别产生警告和严重结果() {
        WeatherViolationDetector detector = new WeatherViolationDetector(parameters());

        DetectionResult warning = detector.feed(8.0, 1_000L);
        DetectionResult duration = detector.feed(13.9, 12_000L);
        DetectionResult critical = detector.feed(14.0, 13_000L);

        assertEquals(DetectionResult.Phase.RAISED, warning.phase());
        assertEquals(AlertLevel.WARN, warning.level());
        assertEquals(8.0, warning.currentValue());
        assertEquals(8.0, warning.thresholdValue());
        assertEquals(DetectionResult.Phase.DURATION, duration.phase());
        assertEquals(AlertLevel.WARN, duration.level());
        assertEquals(DetectionResult.Phase.LEVEL_CHANGED, critical.phase());
        assertEquals(AlertLevel.CRITICAL, critical.level());
        assertEquals(14.0, critical.currentValue());
        assertEquals(14.0, critical.thresholdValue());
    }

    @Test
    void 风速突变立即升级为严重() {
        WeatherViolationDetector detector = new WeatherViolationDetector(parameters());

        DetectionResult warning = detector.feed(8.0, 1_000L);
        DetectionResult surge = detector.feed(9.0, 2_000L);

        assertEquals(AlertLevel.WARN, warning.level());
        assertEquals(DetectionResult.Phase.LEVEL_CHANGED, surge.phase());
        assertEquals(AlertLevel.CRITICAL, surge.level());
        assertEquals(14.0, surge.thresholdValue());
    }

    @Test
    void 突变窗口外的旧采样点不参与变化率计算() {
        WeatherViolationDetector detector = new WeatherViolationDetector(parameters());

        DetectionResult first = detector.feed(0.0, 1_000L);
        DetectionResult later = detector.feed(8.0, 12_000L);

        assertEquals(DetectionResult.Phase.NONE, first.phase());
        assertEquals(DetectionResult.Phase.RAISED, later.phase());
        assertEquals(AlertLevel.WARN, later.level());
    }

    @Test
    void 连续五个低于预警阈值的采样点才清除() {
        WeatherViolationDetector detector = new WeatherViolationDetector(parameters());
        detector.feed(8.0, 1_000L);

        for (int index = 1; index <= 4; index++) {
            DetectionResult holding = detector.feed(7.0, 1_000L + index * 1_000L);
            assertEquals(DetectionResult.Phase.DURATION, holding.phase());
            assertEquals(AlertLevel.WARN, holding.level());
        }
        DetectionResult cleared = detector.feed(7.0, 6_000L);

        assertEquals(DetectionResult.Phase.CLEARED, cleared.phase());
    }

    @Test
    void 回升到预警区间会重置连续清除计数() {
        WeatherViolationDetector detector = new WeatherViolationDetector(parameters());
        detector.feed(8.0, 1_000L);
        detector.feed(7.0, 2_000L);
        detector.feed(7.0, 3_000L);
        detector.feed(8.5, 8_000L);

        for (int index = 9; index <= 12; index++) {
            assertEquals(DetectionResult.Phase.DURATION,
                    detector.feed(7.0, index * 1_000L).phase());
        }

        assertEquals(DetectionResult.Phase.CLEARED, detector.feed(7.0, 13_000L).phase());
    }
}
