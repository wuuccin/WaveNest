package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/**
 * 全域气象超限检测器。
 *
 * <p>检测器只维护一套风况状态，阈值不按无人机或机型拆分。绝对风速越过严重阈值或变化率
 * 越过突变阈值时立即进入严重档；严重档回落和警告档清除均使用连续采样点迟滞。</p>
 */
@Component
public class WeatherViolationDetector {

    private record WindSample(double windSpeedMs, long observedAtMs) {
    }

    private final SysParamProvider params;
    private final Deque<WindSample> window = new ArrayDeque<>();

    private AlertLevel currentLevel;
    private double currentWindSpeedMs;
    private int belowCriticalCount;
    private int belowWarningCount;
    private long triggeredAtMs;

    public WeatherViolationDetector(SysParamProvider params) {
        this.params = params;
    }

    /**
     * 喂入一个全域风速采样点。
     *
     * @param windSpeedMs 当前风速，米每秒
     * @param observedAtMs 观测时刻，Unix 毫秒
     * @return 本次采样对应的生命周期结果
     */
    public DetectionResult feed(double windSpeedMs, long observedAtMs) {
        if (!Double.isFinite(windSpeedMs) || windSpeedMs < 0.0) {
            throw new IllegalArgumentException("风速必须为非负有限值");
        }
        if (observedAtMs < 0L) {
            throw new IllegalArgumentException("气象观测时刻不得为负数");
        }
        if (!window.isEmpty() && observedAtMs < window.peekLast().observedAtMs()) {
            throw new IllegalArgumentException("气象观测时刻不得早于已接收采样点");
        }

        currentWindSpeedMs = windSpeedMs;
        pruneWindow(observedAtMs);
        boolean surge = hasSurge(windSpeedMs, observedAtMs);
        window.addLast(new WindSample(windSpeedMs, observedAtMs));

        double warning = params.getWeatherWindWarningThreshold();
        double critical = params.getWeatherWindCriticalThreshold();
        AlertLevel instantLevel = windSpeedMs >= critical || surge
                ? AlertLevel.CRITICAL
                : windSpeedMs >= warning ? AlertLevel.WARN : null;

        if (currentLevel == null) {
            if (instantLevel == null) {
                return DetectionResult.none(AlertType.WEATHER_EXCEED);
            }
            currentLevel = instantLevel;
            triggeredAtMs = observedAtMs;
            belowCriticalCount = 0;
            belowWarningCount = 0;
            return result(observedAtMs, DetectionResult.Phase.RAISED);
        }

        if (instantLevel == AlertLevel.CRITICAL) {
            belowCriticalCount = 0;
            belowWarningCount = 0;
            if (currentLevel != AlertLevel.CRITICAL) {
                currentLevel = AlertLevel.CRITICAL;
                return result(observedAtMs, DetectionResult.Phase.LEVEL_CHANGED);
            }
            return result(observedAtMs, DetectionResult.Phase.DURATION);
        }

        if (currentLevel == AlertLevel.CRITICAL) {
            belowWarningCount = 0;
            belowCriticalCount++;
            if (belowCriticalCount >= params.getWeatherClearDebounce()) {
                currentLevel = AlertLevel.WARN;
                belowCriticalCount = 0;
                return result(observedAtMs, DetectionResult.Phase.LEVEL_CHANGED);
            }
            return result(observedAtMs, DetectionResult.Phase.DURATION);
        }

        belowCriticalCount = 0;
        if (instantLevel == AlertLevel.WARN) {
            belowWarningCount = 0;
            return result(observedAtMs, DetectionResult.Phase.DURATION);
        }

        belowWarningCount++;
        if (belowWarningCount >= params.getWeatherClearDebounce()) {
            reset();
            return DetectionResult.cleared(AlertType.WEATHER_EXCEED);
        }
        return result(observedAtMs, DetectionResult.Phase.DURATION);
    }

    /** 清空全域判档状态和风速窗口。 */
    public void reset() {
        currentLevel = null;
        currentWindSpeedMs = 0.0;
        belowCriticalCount = 0;
        belowWarningCount = 0;
        triggeredAtMs = 0L;
        window.clear();
    }

    private void pruneWindow(long observedAtMs) {
        long cutoffMs = observedAtMs - params.getWeatherSurgeWindowSec() * 1000L;
        while (!window.isEmpty() && window.peekFirst().observedAtMs() < cutoffMs) {
            window.removeFirst();
        }
    }

    private boolean hasSurge(double windSpeedMs, long observedAtMs) {
        double threshold = params.getWeatherWindSurgeThreshold();
        for (WindSample sample : window) {
            long elapsedMs = observedAtMs - sample.observedAtMs();
            if (elapsedMs <= 0L) {
                continue;
            }
            double rate = (windSpeedMs - sample.windSpeedMs()) * 1000.0 / elapsedMs;
            if (rate >= threshold) {
                return true;
            }
        }
        return false;
    }

    private DetectionResult result(long observedAtMs, DetectionResult.Phase phase) {
        double threshold = currentLevel == AlertLevel.CRITICAL
                ? params.getWeatherWindCriticalThreshold()
                : params.getWeatherWindWarningThreshold();
        long durationSeconds = Math.max(0L, observedAtMs - triggeredAtMs) / 1000L;
        return new DetectionResult(
                AlertType.WEATHER_EXCEED,
                currentLevel,
                currentWindSpeedMs,
                threshold,
                durationSeconds,
                phase == DetectionResult.Phase.RAISED,
                triggeredAtMs,
                phase);
    }
}
