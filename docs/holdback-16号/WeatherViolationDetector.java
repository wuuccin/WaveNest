package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 气象超限检测器（扩项）。
 *
 * <p>双逻辑（G 组 §扩）：</p>
 * <ul>
 *   <li>绝对值分级：{@code v_wind ≥ critical} → 严重；{@code warn ≤ v_wind < critical} → 警告</li>
 *   <li>突变判定：{@code surgeWindow} 秒内 {@code Δv/Δt ≥ surgeThreshold} → 突变告警（至少警告，
 *       同时满足绝对值严重取严重）</li>
 *   <li>清除迟滞：连续 {@code clearDebounce} 点低于预警阈值 **且无突变** 才清除</li>
 * </ul>
 *
 * <p>输入风速来自 {@code data.weather} 主题的 {@code WeatherData} 对象（契约已定，M4 为消费方）；
 * 本检测器保持纯计算，只接收风速与时间戳，不感知 MQ。</p>
 */
public class WeatherViolationDetector {

    private final SysParamProvider params;

    /** 滑动窗口：用于突变检测 */
    private final Deque<double[]> window = new ArrayDeque<>(); // [timestampMs, windSpeedMs]

    private AlertLevel currentLevel;
    private boolean surgeActive;
    private int consecutiveClear;
    /** 严重→预警降级连续计数（处于预警区间且无突变） */
    private int consecutiveInWarningZone;
    private long triggeredAtMs;
    private double currentWind;

    public WeatherViolationDetector(SysParamProvider params) {
        this.params = params;
    }

    /**
     * 喂入新的风速样本。
     *
     * @param windSpeedMs 风速（m/s）
     * @param tsMs        采样时间戳（毫秒）
     * @param nowMs       当前系统时间戳（毫秒）
     */
    public DetectionResult feed(double windSpeedMs, long tsMs, long nowMs) {
        this.currentWind = windSpeedMs;

        // 1. 绝对值分级
        double warnT = params.getWeatherWindWarningThreshold();
        double critT = params.getWeatherWindCriticalThreshold();

        AlertLevel absLevel;
        if (windSpeedMs >= critT) {
            absLevel = AlertLevel.CRITICAL;
        } else if (windSpeedMs >= warnT) {
            absLevel = AlertLevel.WARN;
        } else {
            absLevel = null;
        }

        // 2. 突变检测（滑动窗口）
        int surgeWinSec = params.getWeatherSurgeWindowSec();
        double surgeT = params.getWeatherWindSurgeThreshold();

        // 清理过期样本
        window.addLast(new double[]{tsMs, windSpeedMs});
        long cutoff = tsMs - surgeWinSec * 1000L;
        while (!window.isEmpty() && window.peekFirst()[0] < cutoff) {
            window.removeFirst();
        }

        surgeActive = detectSurge(surgeT);

        // 综合判定：绝对值或突变 → 至少警告，两者取最严重
        AlertLevel instantLevel = null;
        if (absLevel != null && surgeActive) {
            instantLevel = AlertLevel.CRITICAL; // 同时满足取严重
        } else if (absLevel != null) {
            instantLevel = absLevel;
        } else if (surgeActive) {
            instantLevel = AlertLevel.WARN; // 突变本身至少警告
        }

        // 3. 去抖 / 升级 / 清除
        if (currentLevel == null) {
            if (instantLevel != null) {
                currentLevel = instantLevel;
                triggeredAtMs = nowMs;
                consecutiveClear = 0;
                return buildResult(nowMs, true);
            }
            consecutiveClear = 0;
            return DetectionResult.none(AlertType.WEATHER_EXCEED);
        } else {
            // 有告警
            if (instantLevel == null) {
                consecutiveClear++;
                consecutiveInWarningZone = 0;
                int clearN = params.getWeatherClearDebounce();
                if (consecutiveClear >= clearN) {
                    currentLevel = null;
                    triggeredAtMs = 0;
                    consecutiveClear = 0;
                    return DetectionResult.none(AlertType.WEATHER_EXCEED);
                }
                return buildResult(nowMs, false);
            }

            // 升级：即时（预警 → 严重）
            if (instantLevel == AlertLevel.CRITICAL && currentLevel == AlertLevel.WARN) {
                currentLevel = AlertLevel.CRITICAL;
                consecutiveClear = 0;
                consecutiveInWarningZone = 0;
                return buildResult(nowMs, false);
            }

            // 降级：严重 → 预警——风速持续 < 严重阈值且无突变，
            // 连续 weather_clear_debounce 点满足才降级（迟滞，避免阵风间隙级别反复）
            if (currentLevel == AlertLevel.CRITICAL && instantLevel == AlertLevel.WARN) {
                consecutiveInWarningZone++;
                consecutiveClear = 0;
                int clearN = params.getWeatherClearDebounce();
                if (consecutiveInWarningZone >= clearN) {
                    currentLevel = AlertLevel.WARN;
                    consecutiveInWarningZone = 0;
                }
                return buildResult(nowMs, false);
            }

            consecutiveClear = 0;
            consecutiveInWarningZone = 0;
            return buildResult(nowMs, false);
        }
    }

    /** 检测窗口内是否有突变（变化率 ≥ surgeThreshold）。 */
    private boolean detectSurge(double surgeThreshold) {
        if (window.size() < 2) return false;
        double[] first = window.peekFirst();
        double[] last = window.peekLast();
        double dtSec = (last[0] - first[0]) / 1000.0;
        if (dtSec <= 0) return false;
        double dv = Math.abs(last[1] - first[1]);
        return (dv / dtSec) >= surgeThreshold;
    }

    public void reset() {
        currentLevel = null;
        surgeActive = false;
        consecutiveClear = 0;
        consecutiveInWarningZone = 0;
        triggeredAtMs = 0;
        window.clear();
    }

    private DetectionResult buildResult(long nowMs, boolean newlyTriggered) {
        long durationSec = triggeredAtMs > 0 ? (nowMs - triggeredAtMs) / 1000 : 0;
        double threshold = (currentLevel == AlertLevel.CRITICAL)
                ? params.getWeatherWindCriticalThreshold()
                : params.getWeatherWindWarningThreshold();
        return new DetectionResult(
                AlertType.WEATHER_EXCEED, currentLevel, currentWind, threshold,
                durationSec, newlyTriggered, triggeredAtMs);
    }
}
