package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import org.junit.jupiter.api.Test;

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

        // OT-3: 60m → 立即升级严重
        assertEquals(AlertLevel.CRITICAL, det.feed(60.0, 5000).level(), "OT-3 升级严重（即时）");

        // OT-4: 40m × 3 → 降级警告（去抖）
        det.feed(40.0, 6000);
        det.feed(40.0, 7000);
        assertEquals(AlertLevel.WARN, det.feed(40.0, 8000).level(), "OT-4 严重→警告降级（3 点去抖）");

        // OT-5: 25m × 5 → 清除
        for (int i = 0; i < 4; i++) det.feed(25.0, 9000 + i * 1000L);
        assertFalse(det.feed(25.0, 13000).hasAlert(), "OT-5 连续 5 点回阈内清除");
    }
}
