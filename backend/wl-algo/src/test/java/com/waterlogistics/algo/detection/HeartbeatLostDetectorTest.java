package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import org.junit.jupiter.api.Test;

import static com.waterlogistics.algo.detection.DetectionResult.Phase.DURATION;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.LOSS_DECLARED;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.NONE;
import static com.waterlogistics.algo.detection.DetectionResult.Phase.RAISED;
import static com.waterlogistics.algo.detection.HeartbeatLostDetector.HeartbeatOutcome.ACCEPTED;
import static com.waterlogistics.algo.detection.HeartbeatLostDetector.HeartbeatOutcome.RECOVERED;
import static com.waterlogistics.algo.detection.HeartbeatLostDetector.HeartbeatOutcome.STALE_IGNORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失联检测器验证（功能 26）。
 * 默认窗口：失联 30s、灭失 120s、轮询 5s（{@link SysParamProvider#defaults()}）。
 * 级别口径（2026-10-16 裁决，方案 B）：失联＝严重，灭失推定＝严重（同级，凭 LOSS_DECLARED 相位标识深化）。
 */
class HeartbeatLostDetectorTest {

    private HeartbeatLostDetector newDetector() {
        return new HeartbeatLostDetector(SysParamProvider.defaults());
    }

    @Test
    void 乱序与重复心跳不回拨时钟() {
        HeartbeatLostDetector det = newDetector();

        assertEquals(ACCEPTED, det.heartbeat(100_000), "首次心跳接受");
        assertEquals(STALE_IGNORED, det.heartbeat(90_000), "迟到帧必须拒绝");
        assertEquals(STALE_IGNORED, det.heartbeat(100_000), "等值重复帧必须拒绝");

        // 若时钟被回拨到 90s，121s 就会误触失联；按 100s 基线，131s 才越 30s 窗
        DetectionResult r = det.check(121_000, true);
        assertFalse(r.hasAlert(), "21s 未越失联窗，不得因旧帧回拨误报");
        DetectionResult lost = det.check(131_000, true);
        assertTrue(lost.hasAlert());
        assertEquals(RAISED, lost.phase());
        assertEquals(AlertLevel.CRITICAL, lost.level(), "失联＝严重（方案 B）");
        assertEquals(AlertType.LOST_LINK, lost.alertType());
    }

    @Test
    void 失联持续灭失两级且灭失只强制一次() {
        HeartbeatLostDetector det = newDetector();
        det.heartbeat(0);

        DetectionResult raised = det.check(31_000, true);
        assertEquals(RAISED, raised.phase(), "31s 越 30s 窗触发失联");
        assertTrue(raised.newlyTriggered());
        assertEquals(AlertLevel.CRITICAL, raised.level(), "失联档＝严重");
        assertEquals(30.0, raised.thresholdValue(), "失联档阈值＝失联窗");
        assertEquals(DURATION, det.check(60_000, true).phase(), "持续失联为 DURATION");

        DetectionResult loss = det.check(121_000, true);
        assertEquals(LOSS_DECLARED, loss.phase(), "121s 越 120s 窗推定灭失");
        assertEquals(AlertLevel.CRITICAL, loss.level(), "灭失推定＝严重（与失联同级，凭相位标识）");
        assertEquals(120.0, loss.thresholdValue(), "灭失档阈值＝灭失窗");
        assertTrue(det.isLoss());
        // 灭失后继续轮询回到 DURATION，不得每帧重复强制
        assertEquals(DURATION, det.check(130_000, true).phase());
        assertEquals(DURATION, det.check(140_000, true).phase());
    }

    @Test
    void 失联未灭失收到新心跳即恢复() {
        HeartbeatLostDetector det = newDetector();
        det.heartbeat(0);
        det.check(31_000, true);
        assertTrue(det.isLost());

        assertEquals(RECOVERED, det.heartbeat(60_000), "失联后灭失前新心跳 → 恢复信号");
        assertFalse(det.isLost());
        assertFalse(det.check(61_000, true).hasAlert(), "恢复后下一帧无告警");

        // 恢复后再次失联应作为全新告警 RAISED
        assertEquals(RAISED, det.check(91_000, true).phase());
    }

    @Test
    void 灭失后迟到心跳不自动复活须M2复核复位() {
        HeartbeatLostDetector det = newDetector();
        det.heartbeat(0);
        det.check(31_000, true);
        det.check(121_000, true);
        assertTrue(det.isLoss());

        // G-12：灭失撤销由 M2 发起；迟到新心跳只刷新时钟，不清除灭失态
        assertEquals(ACCEPTED, det.heartbeat(130_000));
        assertTrue(det.isLoss(), "迟到心跳不得自动撤销灭失");
        assertEquals(DURATION, det.check(131_000, true).phase());

        det.reinstateAfterM2Review(200_000);
        assertFalse(det.isLoss());
        assertFalse(det.isLost());
        // 以 M2 确认时刻重建基线：201s 轮询只过 1s，无告警
        assertFalse(det.check(201_000, true).hasAlert());
    }

    @Test
    void 地面机不误报且无心跳不检测() {
        HeartbeatLostDetector det = newDetector();
        det.heartbeat(0);
        DetectionResult ground = det.check(100_000, false);
        assertEquals(NONE, ground.phase());
        assertFalse(ground.hasAlert(), "非执行任务中/返航中不检测");
        assertFalse(det.isLost());

        HeartbeatLostDetector never = newDetector();
        assertFalse(never.check(100_000, true).hasAlert(), "从未收到心跳不检测");
    }
}
