package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import org.junit.jupiter.api.Test;

import static com.waterlogistics.algo.detection.DetectionResult.Phase.CLEARED;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.DURATION;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.LEVEL_CHANGED;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.NONE;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.RAISED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 低电量检测器验证（功能 25，16 号归位）。
 * 默认阈值：trigger=20%、clear=30%、severe=10%、降级去抖 3 点
 * （{@link SysParamProvider#defaults()}）。
 */
class LowBatteryDetectorTest {

    @Test
    void unifiedTriggerUsesTwentyPercentBoundary() {
        assertEquals(20.0, SysParamProvider.defaults().getLowBatteryTrigger());
        assertEquals(NONE, newDetector().feed(20.0, 1_000).phase());
        DetectionResult belowTrigger = newDetector().feed(19.99, 1_000);
        assertEquals(RAISED, belowTrigger.phase());
        assertEquals(AlertLevel.WARN, belowTrigger.level());
        assertEquals(20.0, belowTrigger.thresholdValue());
    }

    private LowBatteryDetector newDetector() {
        return new LowBatteryDetector(SysParamProvider.defaults());
    }

    @Test
    void 完整迟滞分级与清除后再触发() {
        LowBatteryDetector det = newDetector();

        // 15% < 20% 但 ≥ 10% → 警告，阈值带出 trigger=20
        DetectionResult warn = det.feed(15.0, 1_000);
        assertEquals(RAISED, warn.phase());
        assertEquals(AlertLevel.WARN, warn.level());
        assertEquals(20.0, warn.thresholdValue());
        assertEquals(1_000, warn.triggeredAtMs());

        // 5% < severe → 立即升级严重（无去抖），阈值带出 severe=10
        DetectionResult crit = det.feed(5.0, 2_000);
        assertEquals(LEVEL_CHANGED, crit.phase());
        assertEquals(AlertLevel.CRITICAL, crit.level());
        assertEquals(10.0, crit.thresholdValue());
        // 级别变化不重置首次触发时刻，duration 从首发累计
        assertEquals(1, crit.durationSeconds());

        // 严重持续 → DURATION
        assertEquals(DURATION, det.feed(5.0, 3_000).phase());

        // 12% ≥ severe：前两点降级去抖未达标，第三点才 LEVEL_CHANGED
        assertEquals(DURATION, det.feed(12.0, 4_000).phase());
        assertEquals(DURATION, det.feed(12.0, 5_000).phase());
        DetectionResult downgraded = det.feed(12.0, 6_000);
        assertEquals(LEVEL_CHANGED, downgraded.phase());
        assertEquals(AlertLevel.WARN, downgraded.level());

        // 35% > clear=30 → 清除
        DetectionResult cleared = det.feed(35.0, 7_000);
        assertEquals(CLEARED, cleared.phase());
        assertFalse(cleared.hasAlert());

        // 清除后再次越阈 → 全新 RAISED
        DetectionResult again = det.feed(15.0, 8_000);
        assertEquals(RAISED, again.phase());
        assertTrue(again.newlyTriggered());
    }

    @Test
    void 首帧低于severe直接判严重() {
        LowBatteryDetector det = newDetector();
        DetectionResult r = det.feed(5.0, 1_000);
        assertEquals(RAISED, r.phase());
        assertEquals(AlertLevel.CRITICAL, r.level());
        assertEquals(10.0, r.thresholdValue());
    }

    @Test
    void 阈值之上不触发且警告区间电量回升不过clear不解除() {
        LowBatteryDetector det = newDetector();
        assertEquals(NONE, det.feed(50.0, 1_000).phase());
        assertEquals(NONE, det.feed(20.0, 2_000).phase(), "battery < trigger 才触发，等号不触发");

        DetectionResult warn = det.feed(19.0, 3_000);
        assertEquals(RAISED, warn.phase());
        // 25% 虽高于 trigger，但未过 clear=30，告警维持
        assertEquals(DURATION, det.feed(25.0, 4_000).phase());
        // 等号 30 也不解除（严格 > clear）
        assertEquals(DURATION, det.feed(30.0, 5_000).phase());
        assertEquals(CLEARED, det.feed(30.01, 6_000).phase());
    }
}
