package com.waterlogistics.contract.rest;

import java.util.List;

/**
 * 派单闸门状态摘要——依据《22-接口契约》v1.2 §3.2；响应级确认字段见
 * 《1112-功能做实批次｜接口约定》§3.4。
 *
 * <p>响应级字段，出现在 {@code GET /api/orders} 与 {@code POST /api/intervene} 的响应里。
 * 由 M2 后端读取，M5 不自建第二个冻结开关、不自行判断气象是否熔断；WS 不另设闸门消息。
 *
 * <p>{@code GET /api/orders} 走 {@link #DispatchGate(GateStatus, List)} 两参构造，
 * 无确认语义；{@code POST /api/intervene} 成功后由 {@link #withConfirmation(String)}
 * 在同一份闸门快照上叠加确认信息，保证「状态 + 来源 + 确认」成对回吐。
 *
 * @param gateStatus  闸门状态：{@link GateStatus}
 * @param gateSources 熔断来源列表：{@link GateSource}；正常时为空数组
 * @param confirmed   {@code true} 表示后端已执行本次 action
 * @param message     成功为空串，失败写《1112》§3.3 的具体原因
 * @param orderId     本次操作涉及的订单号，无则 {@code null}
 */
public record DispatchGate(
        GateStatus gateStatus,
        List<GateSource> gateSources,
        boolean confirmed,
        String message,
        String orderId) {

    /** 非干预场景（{@code GET /api/orders}）：不带确认语义、不带订单号。 */
    public DispatchGate(GateStatus gateStatus, List<GateSource> gateSources) {
        this(gateStatus, gateSources, false, "", null);
    }

    /** 在既有闸门快照上叠加本次干预的确认信息（成功时 message 恒为空串）。 */
    public DispatchGate withConfirmation(String actionOrderId) {
        return new DispatchGate(gateStatus, gateSources, true, "", actionOrderId);
    }

    /** 在既有闸门快照上回吐一次未确认结果（业务动作未执行时由调用方填具体原因）。 */
    public DispatchGate withRejection(String reason, String actionOrderId) {
        return new DispatchGate(gateStatus, gateSources, false, reason, actionOrderId);
    }

    /** 闸门是否处于熔断中 */
    public boolean isBroken() {
        return gateStatus == GateStatus.BROKEN;
    }
}
