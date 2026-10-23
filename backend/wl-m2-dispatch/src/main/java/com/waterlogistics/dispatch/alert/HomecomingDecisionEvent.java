package com.waterlogistics.dispatch.alert;

import com.waterlogistics.algo.inflight.HomecomingTier;

/**
 * 返航复核结论广播（F1 · 2026-10-23）。
 *
 * <p>告警驱动的返航复核产出三级落点结论后发布本事件：
 * <b>决策落审计（task_log）＋进程内广播</b>即 F1 的终点——返航复核的三个下游动作
 * （返航航迹、归巢时隙占位、返航执行指令）《10-23 清单》明确<b>保持不接实</b>，
 * 本事件就是后续接链方的挂点（组装层可投影为 WS 帧，B/D 组可消费）。
 *
 * @param eventId          触发复核的告警事件 id（贯穿始终，同 {@code AlertEventMessage.eventId}）
 * @param alertType        告警类型中文值（低电量／失联）
 * @param orderId          订单标识
 * @param droneId          无人机标识
 * @param tier             三级落点结论（回原巢／迫停他巢／迫降野外）
 * @param targetId         落点目标标识（第三级迫降野外时可为 null）
 * @param needSlotRequest  是否需向 D 组发起归巢时隙占位（仅第二级为 true）
 * @param reason           判定说明（含候选他巢逐项打叉审计摘要）
 */
public record HomecomingDecisionEvent(
        String eventId,
        String alertType,
        String orderId,
        String droneId,
        HomecomingTier tier,
        String targetId,
        boolean needSlotRequest,
        String reason) {
}
