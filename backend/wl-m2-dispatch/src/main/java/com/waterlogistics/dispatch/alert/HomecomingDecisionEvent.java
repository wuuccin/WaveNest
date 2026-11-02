package com.waterlogistics.dispatch.alert;

import com.waterlogistics.algo.inflight.HomecomingTier;

/**
 * 返航复核结论广播（F1 · 2026-10-23）。
 *
 * <p>告警驱动的返航复核产出三级落点结论后发布本事件：
 * <b>决策落审计（task_log）＋进程内广播</b>是返航复核的对外出口——
 * 组装层可投影为 WS 帧，B/D 组可消费；返航航迹、归巢时隙占位、返航指令由
 * {@code HomecomingService} 在本组内接实。
 *
 * <p><b>MEDIUM.2（可维护）</b>：{@code needSlotRequest} 不再作为独立字段随构造传入，
 * 改由 {@link #tier()} 派生——占位请求<b>只可能</b>出现在第二级，避免广播载荷与判定结论不一致。
 *
 * <p><b>MEDIUM.6</b>：构造器强制不变量（必填字段非空、非第三级须有落点目标），
 * 事件一旦发布即保证可审计、可消费。
 *
 * @param eventId   触发复核的告警事件 id（贯穿始终，同 {@code AlertEventMessage.eventId}）
 * @param alertType 告警类型中文值（低电量／失联）
 * @param orderId   订单标识
 * @param droneId   无人机标识
 * @param tier      三级落点结论（回原巢／迫停他巢／迫降野外）
 * @param targetId  落点目标标识（第三级迫降野外时可为 {@code null}，待 L4 迫降点层出点）
 * @param reason    判定说明（含候选他巢逐项打叉审计摘要）
 */
public record HomecomingDecisionEvent(
        String eventId,
        String alertType,
        String orderId,
        String droneId,
        HomecomingTier tier,
        String targetId,
        String reason) {

    public HomecomingDecisionEvent {
        requireText(eventId, "eventId");
        requireText(alertType, "alertType");
        requireText(orderId, "orderId");
        requireText(droneId, "droneId");
        requireText(reason, "reason");
        if (tier == null) {
            throw new IllegalArgumentException("tier 不可为空：广播必须带三级落点结论");
        }
        // 第三级允许缺目标（L4 迫降点层未接）；第一、二级必须有落点标识
        if (tier != HomecomingTier.EMERGENCY_LAND_FIELD && (targetId == null || targetId.isBlank())) {
            throw new IllegalArgumentException(
                    "非第三级须带 targetId（落点标识），当前级别=" + tier.getValue());
        }
    }

    /** 是否需向 D 组发起归巢时隙占位——由级别派生，仅第二级为 {@code true}。 */
    public boolean needSlotRequest() {
        return tier == HomecomingTier.FORCE_STOP_OTHER_NEST;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不可为空：广播事件须可审计");
        }
    }
}
