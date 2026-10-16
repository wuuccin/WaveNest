package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;

/**
 * {@code POST /api/orders} 的响应体（M2 · 卢肄文）。
 *
 * <p><b>为什么不用 HTTP 状态码承载业务拒绝</b>：请求本身是良构的，被拒是<b>业务判定</b>
 * 的结果（{@code 80-A组算法方案} §3.1 的判定链），故统一 200 ＋ {@code accepted} 标志 ＋
 * {@code reasonCode}，前端据 {@code reasonCode} 提示原因即可；不因业务拒绝返回 4xx，
 * 避免前端把「业务拒绝」误判为「接口故障」。
 *
 * @param accepted   是否受理通过
 * @param orderId    订单标识（受理通过才有值）
 * @param order      订单展示视图（受理通过才有值），字段见 {@link OrderView}
 * @param reasonCode 拒绝原因码（受理通过时为 null），取值见
 *                   {@link OrderAdmissionService.RejectReason}
 * @param message    拒绝原因说明（供前端直接展示）
 */
public record OrderSubmitResponse(
        boolean accepted,
        String orderId,
        OrderView order,
        String reasonCode,
        String message) {

    /** 受理通过。 */
    public static OrderSubmitResponse accepted(OrderView order) {
        return new OrderSubmitResponse(true, order.orderId(), order, null, null);
    }

    /** 受理拒绝。 */
    public static OrderSubmitResponse rejected(String reasonCode, String message) {
        return new OrderSubmitResponse(false, null, null, reasonCode, message);
    }
}
