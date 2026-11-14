package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.contract.state.AlertLevel;

/**
 * 在飞安全复核结论广播（气象超限路径）。
 *
 * <p><b>发不发由结论决定</b>：继续档<b>不发</b>——它不构成在飞风险，广播只会让前端多一条无意义刷新；
 * 调整与终止两档发（见 {@link InflightSafetyVerdict.Verdict} 表）。
 *
 * <p><b>终止档为何带落点</b>：终止＝本次任务作废，交功能 10 定落点；落点结论已由
 * {@code HomecomingService} 产出，随本事件一并广播，前端不必再等另一条
 * {@code HomecomingDecisionEvent}。落点缺失（缺机巢／缺会面点／电量不可得）时
 * {@code tier} 与 {@code targetId} 为空，事件仍发——终止这个事实本身必须可见，
 * 不能因为落点算不出来就连「任务已作废」都不告诉前端。
 *
 * @param eventId     触发复核的告警事件 id（贯穿始终，与 {@code alert} 台账对齐）
 * @param orderId     订单标识（反查后的权威单号，非请求里的可空值）
 * @param droneId     无人机标识
 * @param verdict     三值结论（继续／调整／终止；继续档不会构造本事件）
 * @param windSpeedMs 判档时的风速（m/s）
 * @param alertLevel  判档时的告警级别
 * @param reason      判档说明
 * @param tier        功能 10 落点结论；仅终止档可能有值，算不出落点时为 {@code null}
 * @param targetId    落点目标标识；仅终止档可能有值
 */
public record InflightSafetyDecisionEvent(
        String eventId,
        String orderId,
        String droneId,
        InflightSafetyVerdict.Verdict verdict,
        double windSpeedMs,
        AlertLevel alertLevel,
        String reason,
        HomecomingTier tier,
        String targetId) {

    public InflightSafetyDecisionEvent {
        requireText(eventId, "eventId");
        requireText(orderId, "orderId");
        requireText(droneId, "droneId");
        requireText(reason, "reason");
        if (verdict == null) {
            throw new IllegalArgumentException("verdict 不可为空：广播必须带复核结论");
        }
        if (verdict == InflightSafetyVerdict.Verdict.CONTINUE) {
            throw new IllegalArgumentException("继续档不发广播：构造本事件即错用（见契约 §3.3）");
        }
        // targetId 只在有 tier 时才有意义，反过来也一样：有落点必有级别
        if (tier == null && targetId != null) {
            throw new IllegalArgumentException("缺 tier 却带 targetId：" + targetId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不可为空：广播事件须可审计");
        }
    }
}
