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
 * <p><b>三种出口</b>（阶段一 26 接线后）：
 * <ul>
 *   <li>受理并调度——{@code accepted=true}，{@code waitReason=null}；</li>
 *   <li>延后待唤醒——{@code accepted=true}，{@code waitReason} 非 null
 *       （{@code WAIT_SHIP}／{@code WAIT_CARGO}／{@code GATE_OR_DATA}），
 *       订单进「已受理待唤醒」，由唤醒链路在 {@code wakeEtaTs} 前后重推调度；</li>
 *   <li>拒绝／转人工——{@code accepted=false} ＋ {@code reasonCode}。</li>
 * </ul>
 *
 * @param accepted   是否受理（延后也算受理，只是不立即进调度）
 * @param orderId    订单标识（受理或延后都有值）
 * @param order      订单展示视图（受理或延后都有值），字段见 {@link OrderView}
 * @param reasonCode 原因码：拒绝时为拒绝原因；延后时为判定链原因（如 {@code SHIP_OUT_OF_SEGMENT}）；
 *                   受理并调度时为 null
 * @param message    原因说明（供前端直接展示）
 * @param waitReason 延后等待原因（仅延后非 null），取值见
 *                   {@link com.waterlogistics.contract.rest.WaitReason}
 * @param wakeEtaTs  预计唤醒时刻，Unix 毫秒（仅延后且可估计时非 null）
 * @param etaTs      预计送达时刻，Unix 毫秒；未算出时 {@code null}
 */
public record OrderSubmitResponse(
        boolean accepted,
        String orderId,
        OrderView order,
        String reasonCode,
        String message,
        String waitReason,
        Long wakeEtaTs,
        Long etaTs) {

    /**
     * 兼容 11-05 之前的 7 参构造（不带 {@code etaTs}）。
     *
     * <p>保留理由：{@code etaTs} 的来源是 {@code order_task.eta_ts} 列，而该列由张睿哲的
     * {@code 010_order_cargo_eta.sql} 新增；在迁移合入之前，本响应的取值恒为
     * {@code null}，与旧构造器等价。调用点不必为「列还没建」这件事改一遍。
     */
    public OrderSubmitResponse(boolean accepted, String orderId, OrderView order, String reasonCode,
            String message, String waitReason, Long wakeEtaTs) {
        this(accepted, orderId, order, reasonCode, message, waitReason, wakeEtaTs, null);
    }

    /** 受理并调度。{@code etaTs} 取自订单视图（同一来源 {@code order_task.eta_ts}）。 */
    public static OrderSubmitResponse accepted(OrderView order) {
        return new OrderSubmitResponse(true, order.orderId(), order, null, null, null, null,
                order == null ? null : order.etaTs());
    }

    /** 延后待唤醒：受理通过但不进调度，等待唤醒。 */
    public static OrderSubmitResponse deferred(OrderView order, String reasonCode, String message,
            String waitReason, Long wakeEtaTs) {
        return new OrderSubmitResponse(true, order.orderId(), order, reasonCode, message,
                waitReason, wakeEtaTs, order == null ? null : order.etaTs());
    }

    /** 受理拒绝（含转人工：转人工用 {@code reasonCode=INCOMPLETE} 表达）。 */
    public static OrderSubmitResponse rejected(String reasonCode, String message) {
        return new OrderSubmitResponse(false, null, null, reasonCode, message, null, null, null);
    }
}
