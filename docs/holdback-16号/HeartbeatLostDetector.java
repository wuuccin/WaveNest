package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 失联检测器（功能 26）。
 *
 * <p>两级窗口（G-13）：</p>
 * <ul>
 *   <li>失联：{@code now − last_heartbeat > lostWindow} → 写 drone.status=失联</li>
 *   <li>灭失：{@code now − last_heartbeat > lossWindow} 且已失联 → 推定灭失，上报 M2</li>
 *   <li>恢复：失联后灭失前收到遥测 → 清除</li>
 * </ul>
 *
 * <p>本检测器不直接读遥测流，由调用方在每次收到心跳时调 {@link #heartbeat(long)}，
 * 并由调度器按 {@link SysParamProvider#getHeartbeatCheckIntervalSec()} 周期调 {@link #check(long, boolean)}。</p>
 */
public class HeartbeatLostDetector {

    private final SysParamProvider params;

    /** 最后一次心跳时间戳（毫秒） */
    private long lastHeartbeatMs;
    /** 是否收到过至少一次心跳 */
    private boolean received;
    /** 是否已失联 */
    private boolean lost;
    /** 是否已推定灭失 */
    private boolean loss;
    /** 失联触发时间戳 */
    private long lostAtMs;

    public HeartbeatLostDetector(SysParamProvider params) {
        this.params = params;
    }

    /**
     * 收到心跳（遥测打点时间即心跳）。
     *
     * @param tsMs 遥测打点时间戳（毫秒，契约《22》§2 已定 ts 为 Unix 毫秒），G-13 唯一心跳源
     */
    public void heartbeat(long tsMs) {
        this.lastHeartbeatMs = tsMs;
        this.received = true;
        if (lost && !loss) {
            // 失联后灭失前收到心跳 → 恢复
            lost = false;
            lostAtMs = 0;
        }
    }

    /**
     * 执行一次失联检测（由外部定时器按 checkInterval 触发）。
     *
     * @param nowMs            当前系统时间戳（毫秒）
     * @param inFlight         无人机是否在执行任务中/返航中（地面机不检测）
     * @return 检测结果
     */
    public DetectionResult check(long nowMs, boolean inFlight) {
        if (!inFlight) {
            // 地面机不误报
            return DetectionResult.none(AlertType.LOST_LINK);
        }
        if (!received) {
            // 从未收到心跳 → 不检测
            return DetectionResult.none(AlertType.LOST_LINK);
        }

        long elapsedSec = (nowMs - lastHeartbeatMs) / 1000;
        long lostWin = params.getHeartbeatLostWindowSec();
        long lossWin = params.getHeartbeatLossWindowSec();

        if (!lost && elapsedSec > lostWin) {
            // 触发失联
            lost = true;
            lostAtMs = nowMs;
            return buildLostResult(nowMs, true);
        }
        if (lost && !loss && elapsedSec > lossWin) {
            // 推定灭失（仍以失联告警上报）
            loss = true;
            return buildLostResult(nowMs, false);
        }
        if (lost) {
            // 持续失联中
            return buildLostResult(nowMs, false);
        }
        return DetectionResult.none(AlertType.LOST_LINK);
    }

    public boolean isLost() { return lost; }
    public boolean isLoss() { return loss; }

    public void reset() {
        lastHeartbeatMs = 0;
        received = false;
        lost = false;
        loss = false;
        lostAtMs = 0;
    }

    private DetectionResult buildLostResult(long nowMs, boolean newlyTriggered) {
        long durationSec = lostAtMs > 0 ? (nowMs - lostAtMs) / 1000 : 0;
        return new DetectionResult(
                AlertType.LOST_LINK, AlertLevel.CRITICAL, null,
                (double) params.getHeartbeatLostWindowSec(),
                durationSec, newlyTriggered, lostAtMs);
    }
}
