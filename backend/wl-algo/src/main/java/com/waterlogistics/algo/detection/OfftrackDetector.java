package com.waterlogistics.algo.detection;

import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 航线偏离检测器（功能 24）。
 *
 * <p>消费 F 组 {@code RouteMeasurement.project(...)} 产出的 {@code Projection.deviationDistance()}
 * （量测归口彭紫情／algo.measurement，U 裁定 2026-10-16），执行分级 + 去抖 + 持续时长更新；
 * 本检测器不再自行计算几何量。阈值全部从 {@link SysParamProvider} 读取，不硬编码。</p>
 *
 * <p>去抖规则（G 组 `86` §2.3）：</p>
 * <ul>
 *   <li>触发：连续 {@code debounce} 点越阈才触发</li>
 *   <li>清除：连续 {@code clear} 点回到阈值内才清除</li>
 *   <li>升级不做迟滞（≥严重立即升级）</li>
 *   <li>回落严重→预警：连续 {@code downgrade} 点满足才降级</li>
 * </ul>
 */
public class OfftrackDetector {

    private final SysParamProvider params;

    /** 当前告警级别（null = 无告警） */
    private AlertLevel currentLevel;
    /** 当前触发状态下的偏航距离（米） */
    private double currentDeviationMeters;
    /** 连续越阈计数（用于触发去抖） */
    private int consecutiveOverThreshold;
    /** 连续回阈内计数（用于清除去抖） */
    private int consecutiveUnderThreshold;
    /** 严重→预警降级连续计数 */
    private int consecutiveInWarningZone;
    /** 告警首次触发时间戳（毫秒） */
    private long triggeredAtMs;

    public OfftrackDetector(SysParamProvider params) {
        this.params = params;
    }

    /**
     * 接线层入口：喂入 F 组量测投影，偏航距离直接取 {@code Projection.deviationDistance()}。
     *
     * @param projection {@code RouteMeasurement.project(route, current, ts)} 的投影结果
     * @param nowMs      该样本的检测时刻（毫秒），通常取投影 inputTs
     * @return 检测结果（含级别、持续时长等）
     */
    public DetectionResult feed(RouteMeasurement.Projection projection, long nowMs) {
        return feed(projection.deviationDistance(), nowMs);
    }

    /**
     * 状态机核心：喂入一个新的偏航距离样本（米，由 F 组 {@code RouteMeasurement} 产出），
     * 返回本次检测结果。接线层应优先使用 {@link #feed(RouteMeasurement.Projection, long)}。
     *
     * @param deviationMeters 当前位置到当前航段的垂直距离（米），由 F 组产出
     * @param nowMs           当前系统时间戳（毫秒）
     * @return 检测结果（含级别、持续时长等）
     */
    public DetectionResult feed(double deviationMeters, long nowMs) {
        this.currentDeviationMeters = deviationMeters;

        double warnT = params.getOfftrackWarningThreshold();
        double critT = params.getOfftrackCriticalThreshold();

        // 1. 判级（不去抖，只看当前值）
        AlertLevel instantLevel;
        if (deviationMeters >= critT) {
            instantLevel = AlertLevel.CRITICAL;
        } else if (deviationMeters >= warnT) {
            instantLevel = AlertLevel.WARN;
        } else {
            instantLevel = null;
        }

        if (currentLevel == null) {
            // ===== 当前无告警 → 看是否需要触发 =====
            if (instantLevel != null) {
                consecutiveOverThreshold++;
                consecutiveUnderThreshold = 0;
                int debounce = params.getOfftrackDebounceCount();
                if (consecutiveOverThreshold >= debounce) {
                    // 触发
                    currentLevel = instantLevel;
                    triggeredAtMs = nowMs;
                    consecutiveOverThreshold = 0;
                    return buildResult(nowMs, DetectionResult.Phase.RAISED);
                }
                return DetectionResult.none(AlertType.DEVIATION);
            } else {
                // 无告警且未越阈 → 保持
                consecutiveOverThreshold = 0;
                return DetectionResult.none(AlertType.DEVIATION);
            }
        } else {
            // ===== 当前有告警 → 看级别更新或清除 =====

            // 升级：不做迟滞，立即升级
            if (instantLevel == AlertLevel.CRITICAL && currentLevel == AlertLevel.WARN) {
                currentLevel = AlertLevel.CRITICAL;
                consecutiveOverThreshold = 0;
                consecutiveUnderThreshold = 0;
                consecutiveInWarningZone = 0;
                return buildResult(nowMs, DetectionResult.Phase.LEVEL_CHANGED);
            }

            // 降级：严重→预警需去抖
            if (currentLevel == AlertLevel.CRITICAL && instantLevel == AlertLevel.WARN) {
                consecutiveInWarningZone++;
                int downgrade = params.getOfftrackDowngradeCount();
                DetectionResult.Phase phase = DetectionResult.Phase.DURATION;
                if (consecutiveInWarningZone >= downgrade) {
                    currentLevel = AlertLevel.WARN;
                    consecutiveInWarningZone = 0;
                    phase = DetectionResult.Phase.LEVEL_CHANGED;
                }
                consecutiveUnderThreshold = 0;
                return buildResult(nowMs, phase);
            }

            // 清除：连续 N 点回到阈值内
            if (instantLevel == null) {
                consecutiveUnderThreshold++;
                consecutiveOverThreshold = 0;
                consecutiveInWarningZone = 0;
                int clearN = params.getOfftrackClearCount();
                if (consecutiveUnderThreshold >= clearN) {
                    // 清除：返回 CLEARED 相位，编排层据此调 AlertRecorder.resolve
                    currentLevel = null;
                    consecutiveUnderThreshold = 0;
                    triggeredAtMs = 0;
                    return DetectionResult.cleared(AlertType.DEVIATION);
                }
                return buildResult(nowMs, DetectionResult.Phase.DURATION);
            }

            // instantLevel 与 currentLevel 同级 → 继续
            consecutiveUnderThreshold = 0;
            consecutiveOverThreshold = 0;
            consecutiveInWarningZone = 0;
            return buildResult(nowMs, DetectionResult.Phase.DURATION);
        }
    }

    /** 重置检测器（任务结束 / 无人机换班）。 */
    public void reset() {
        currentLevel = null;
        consecutiveOverThreshold = 0;
        consecutiveUnderThreshold = 0;
        consecutiveInWarningZone = 0;
        triggeredAtMs = 0;
    }

    private DetectionResult buildResult(long nowMs, DetectionResult.Phase phase) {
        long durationSec = (triggeredAtMs > 0) ? (nowMs - triggeredAtMs) / 1000 : 0;
        double currentThreshold = (currentLevel == AlertLevel.CRITICAL)
                ? params.getOfftrackCriticalThreshold()
                : params.getOfftrackWarningThreshold();
        return new DetectionResult(
                AlertType.DEVIATION,
                currentLevel,
                currentDeviationMeters,
                currentThreshold,
                durationSec,
                phase == DetectionResult.Phase.RAISED,
                triggeredAtMs,
                phase
        );
    }
}
