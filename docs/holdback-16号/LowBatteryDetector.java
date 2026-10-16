package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 低电量检测器（功能 25）。
 *
 * <p>只做事实检测与上报，不判断能否完成任务/安全返航（该判定归 M2）。</p>
 *
 * <p>迟滞比较 + 分级（G-4：升级即时、降级迟滞）：</p>
 * <ul>
 *   <li>触发：无告警 &amp; battery &lt; trigger → 触发</li>
 *   <li>清除：有告警 &amp; battery &gt; clear → 清除（clear > trigger）</li>
 *   <li>升级：警告 &amp; battery &lt; severe → 立即升级严重</li>
 *   <li>降级：严重 &amp; battery ≥ severe 连续 N 点 → 降级警告</li>
 * </ul>
 */
public class LowBatteryDetector {

    private final SysParamProvider params;

    private AlertLevel currentLevel;
    private double currentBattery;
    private int consecutiveAboveSevere;
    private long triggeredAtMs;

    public LowBatteryDetector(SysParamProvider params) {
        this.params = params;
    }

    /**
     * 喂入电量样本。
     *
     * @param batteryPercent 电量百分比（0–100，契约《22》§2 已定）
     * @param nowMs          当前时间戳（毫秒）
     */
    public DetectionResult feed(double batteryPercent, long nowMs) {
        this.currentBattery = batteryPercent;

        double trigger = params.getLowBatteryTrigger();
        double clear = params.getLowBatteryClear();
        double severe = params.getLowBatterySevere();

        if (currentLevel == null) {
            // 无告警 → 看是否触发
            if (batteryPercent < trigger) {
                // 自动分级
                currentLevel = (batteryPercent < severe) ? AlertLevel.CRITICAL : AlertLevel.WARN;
                triggeredAtMs = nowMs;
                consecutiveAboveSevere = 0;
                return buildResult(nowMs, true);
            }
            return DetectionResult.none(AlertType.LOW_BATTERY);
        } else {
            // 有告警 → 升级 / 降级 / 清除
            if (batteryPercent > clear) {
                // 清除
                currentLevel = null;
                consecutiveAboveSevere = 0;
                return DetectionResult.none(AlertType.LOW_BATTERY);
            }

            if (currentLevel == AlertLevel.WARN && batteryPercent < severe) {
                // 升级（即时）
                currentLevel = AlertLevel.CRITICAL;
                consecutiveAboveSevere = 0;
                return buildResult(nowMs, false);
            }

            if (currentLevel == AlertLevel.CRITICAL && batteryPercent >= severe) {
                // 降级（去抖）
                consecutiveAboveSevere++;
                int debounce = params.getLowBatteryDowngradeDebounce();
                if (consecutiveAboveSevere >= debounce) {
                    currentLevel = AlertLevel.WARN;
                    consecutiveAboveSevere = 0;
                }
                return buildResult(nowMs, false);
            }

            // 兜底：WARN 维持 或 CRITICAL 下电量回落 severe 以下 → 降级计数清零（保证真连续）
            consecutiveAboveSevere = 0;
            return buildResult(nowMs, false);
        }
    }

    public void reset() {
        currentLevel = null;
        consecutiveAboveSevere = 0;
        triggeredAtMs = 0;
    }

    private DetectionResult buildResult(long nowMs, boolean newlyTriggered) {
        long durationSec = (triggeredAtMs > 0) ? (nowMs - triggeredAtMs) / 1000 : 0;
        // 本检测器只产出 WARN / CRITICAL 两档（契约 AlertLevel 的 INFO 档不在此使用）
        double threshold = (currentLevel == AlertLevel.CRITICAL)
                ? params.getLowBatterySevere()
                : params.getLowBatteryTrigger();
        return new DetectionResult(
                AlertType.LOW_BATTERY, currentLevel, currentBattery, threshold,
                durationSec, newlyTriggered, triggeredAtMs);
    }
}
