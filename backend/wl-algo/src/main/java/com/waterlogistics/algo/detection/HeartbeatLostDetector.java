package com.waterlogistics.algo.detection;

import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;

/**
 * 失联检测器（功能 26）。
 *
 * <p>两级窗口（86 §4.3、G-12/G-13），级别口径（2026-10-16 裁决，方案 B）：</p>
 * <ul>
 *   <li>失联：{@code now − last_heartbeat > lostWindow} → 产 RAISED 告警，<b>级别＝严重</b>，写 drone.status=失联</li>
 *   <li>灭失：失联持续 {@code > lossWindow} → 推定为灭失，产 {@link DetectionResult.Phase#LOSS_DECLARED}，
 *       <b>级别仍为严重</b>（与失联同级，不升级）；编排层凭 LOSS_DECLARED 相位强制补发一次事件、
 *       复用同一 {@code event_id}，M2 据此触发货物灭失/订单异常终止（G-12）；本检测器只产事实</li>
 *   <li>恢复：失联后、灭失推定前收到更新的遥测 → {@link HeartbeatOutcome#RECOVERED}，编排层据此清除复位</li>
 * </ul>
 *
 * <p>门控：仅对「执行任务中／返航中」的无人机检测（missionActive=true），地面机不误报。
 * 心跳唯一来源是遥测打点 ts（G-13）：迟到/重复帧不得回拨心跳时钟；
 * 灭失的人工撤销由 M2 发起（G-12），迟到心跳不自动复活，须走 {@link #reinstateAfterM2Review(long)}。</p>
 *
 * <p>本检测器不直接读遥测流，由调用方在每次收到心跳时调 {@link #heartbeat(long)}，
 * 并由调度器按 {@link SysParamProvider#getHeartbeatCheckIntervalSec()} 周期调
 * {@link #check(long, boolean)}。</p>
 */
public class HeartbeatLostDetector {

    /** 心跳喂入结果，编排层据此决定是否做告警清除复位。 */
    public enum HeartbeatOutcome {
        /** 正常接受（含首次心跳） */
        ACCEPTED,
        /** 迟到/重复帧：ts 不新于最后心跳，已拒绝且未回拨时钟 */
        STALE_IGNORED,
        /** 失联后、灭失推定前链路由恢复：编排层须调 AlertRecorder.resolve(LOST_LINK) */
        RECOVERED
    }

    private final SysParamProvider params;

    /** 最后一次有效心跳时间戳（毫秒） */
    private long lastHeartbeatMs;
    /** 是否收到过至少一次有效心跳 */
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
     * 收到心跳（遥测打点时间即心跳，G-13 唯一心跳源）。
     *
     * @param tsMs 遥测打点时间戳（毫秒，契约《22》§2 已定 ts 为 Unix 毫秒）
     * @return 心跳处置结果；迟到/重复帧返回 {@link HeartbeatOutcome#STALE_IGNORED} 且不回拨时钟
     */
    public HeartbeatOutcome heartbeat(long tsMs) {
        if (received && tsMs <= lastHeartbeatMs) {
            return HeartbeatOutcome.STALE_IGNORED;
        }
        boolean recoverable = lost && !loss;
        lastHeartbeatMs = tsMs;
        received = true;
        if (recoverable) {
            // 失联后灭失前收到更新的心跳 → 恢复；灭失后的迟到心跳不自动复活（G-12 撤销归 M2）
            lost = false;
            lostAtMs = 0;
            return HeartbeatOutcome.RECOVERED;
        }
        return HeartbeatOutcome.ACCEPTED;
    }

    /**
     * 执行一次失联检测（由外部定时器按 checkInterval 触发）。
     *
     * @param nowMs         当前系统时间戳（毫秒）
     * @param missionActive 无人机是否处于执行任务中/返航中（仅这两态检测，地面机传 false）
     * @return 检测结果；相位为 RAISED（失联）／DURATION／LOSS_DECLARED（灭失推定），无告警时为 NONE
     */
    public DetectionResult check(long nowMs, boolean missionActive) {
        if (!missionActive) {
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
            return buildLostResult(nowMs, DetectionResult.Phase.RAISED);
        }
        if (lost && !loss && elapsedSec > lossWin) {
            // 推定灭失：与失联同为严重，以 LOSS_DECLARED 相位标识深化（方案 B，不新建「灭失」类型）
            loss = true;
            return buildLostResult(nowMs, DetectionResult.Phase.LOSS_DECLARED);
        }
        if (lost) {
            // 持续失联中（含灭失后等待 M2 处置期间）
            return buildLostResult(nowMs, DetectionResult.Phase.DURATION);
        }
        return DetectionResult.none(AlertType.LOST_LINK);
    }

    /**
     * M2 人工撤销灭失后复位（G-12：灭失撤销只能由 M2 发起）。
     * 以确认时刻重建心跳基线，后续检测按新时钟运行。
     *
     * @param confirmedAtMs M2 撤销确认时刻（毫秒）
     */
    public void reinstateAfterM2Review(long confirmedAtMs) {
        loss = false;
        lost = false;
        lostAtMs = 0;
        lastHeartbeatMs = confirmedAtMs;
        received = true;
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

    private DetectionResult buildLostResult(long nowMs, DetectionResult.Phase phase) {
        long durationSec = lostAtMs > 0 ? (nowMs - lostAtMs) / 1000 : 0;
        // 失联与灭失同为严重（方案 B，2026-10-16 裁决）；阈值取对应窗口
        double threshold = loss
                ? (double) params.getHeartbeatLossWindowSec()
                : (double) params.getHeartbeatLostWindowSec();
        return new DetectionResult(
                AlertType.LOST_LINK, AlertLevel.CRITICAL, null,
                threshold,
                durationSec, phase == DetectionResult.Phase.RAISED, lostAtMs, phase);
    }
}
