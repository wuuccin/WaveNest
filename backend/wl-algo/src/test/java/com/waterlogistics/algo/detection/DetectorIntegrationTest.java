package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import org.junit.jupiter.api.Test;

import static com.waterlogistics.algo.detection.DetectionResult.Phase.CLEARED;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.LEVEL_CHANGED;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.RAISED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 偏航检测器验证（10-15 清单 §3.7 当日范围）。
 * 验证 G 组算法行为正确性（阈值取自 {@link SysParamProvider#defaults()}）。
 *
 * <p>低电量/失联/气象三类为 16 号任务（清单 §6 今日不做），暂存 docs/holdback-16号。
 */
class DetectorIntegrationTest {

    @Test
    void offtrackDetector() {
        SysParamProvider p = SysParamProvider.defaults();
        OfftrackDetector det = new OfftrackDetector(p);
        // 默认阈值：warn=30m, crit=50m, debounce=3, clear=5, downgrade=3

        // OT-1: 29m < 30m → 无告警
        assertFalse(det.feed(29.0, 1000).hasAlert(), "OT-1 29m < 30m 无告警");

        // OT-2: 连续 3 点越阈触发警告
        det.feed(35.0, 2000);
        det.feed(35.0, 3000);
        DetectionResult r2 = det.feed(35.0, 4000);
        assertEquals(AlertLevel.WARN, r2.level(), "OT-2 连续 3 点越阈触发警告");
        assertEquals(RAISED, r2.phase(), "OT-2 首次触发相位 RAISED");

        // OT-3: 60m → 立即升级严重
        DetectionResult r3 = det.feed(60.0, 5000);
        assertEquals(AlertLevel.CRITICAL, r3.level(), "OT-3 升级严重（即时）");
        assertEquals(LEVEL_CHANGED, r3.phase(), "OT-3 升级帧相位 LEVEL_CHANGED（与低电量检测器同构）");

        // OT-4: 40m × 3 → 降级警告（去抖）
        det.feed(40.0, 6000);
        det.feed(40.0, 7000);
        DetectionResult r4 = det.feed(40.0, 8000);
        assertEquals(AlertLevel.WARN, r4.level(), "OT-4 严重→警告降级（3 点去抖）");
        assertEquals(LEVEL_CHANGED, r4.phase(), "OT-4 降级达标帧相位 LEVEL_CHANGED");

        // OT-5: 25m × 5 → 清除（CLEARED 相位，编排层据此 resolve）
        for (int i = 0; i < 4; i++) {
            DetectionResult waiting = det.feed(25.0, 9000 + i * 1000L);
            assertEquals(AlertLevel.WARN, waiting.level(), "回阈内去抖未达标前保持警告");
        }
        DetectionResult r5 = det.feed(25.0, 13000);
        assertFalse(r5.hasAlert(), "OT-5 连续 5 点回阈内清除");
        assertEquals(CLEARED, r5.phase(), "OT-5 清除帧相位 CLEARED（区别于从未有告警的 NONE）");
    }
}
