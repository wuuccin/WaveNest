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
 * <h3>11-05 序号 2：字段组合在构造期校验（MED-BUG.36 / MED-BUG.37 / LOW-MAINT.10）</h3>
 * <p>改前只有「把字段拼进去」的工厂，没有任何组合约束，于是三种坏响应都能构造出来：
 * {@code accepted=true} 但 {@code orderId=null}（前端拿不到单号却被告知成功）、
 * {@code accepted=false} 但 {@code reasonCode=null}（前端只能弹一个空白提示）、
 * 以及 {@code accepted(null)} 直接在工厂里 NPE（业务拒绝本该 200，却变成 500）。
 * 现在把「哪些字段必须成对出现」写成紧凑构造器里的不变式——<b>坏状态构造不出来，
 * 就不必在每个消费方各写一遍判空</b>。
 *
 * @param accepted   是否受理（延后也算受理，只是不立即进调度）
 * @param orderId    订单标识（{@code accepted=true} 时必有）
 * @param order      订单展示视图（{@code accepted=true} 时必有），字段见 {@link OrderView}
 * @param reasonCode 原因码：拒绝时为拒绝原因；延后时为判定链原因（如 {@code SHIP_OUT_OF_SEGMENT}）；
 *                   受理并调度时为 null。
 *                   取值域为<b>两套枚举名之一</b>：基本校验用
 *                   {@link OrderAdmissionService.RejectReason} 的枚举名（如 {@code MMSI_MISSING}），
 *                   判定链用 {@link AdmissionPipeline.Reason} 的枚举名（如 {@code GOODS_NOT_READY}）；
 *                   两者都是英文大写常量名，前端按取值分支即可
 * @param message    原因说明（供前端直接展示）；拒绝时若为空则用 {@code reasonCode} 兜底，
 *                   保证前端永远有可显示的文本（LOW-MAINT.10）
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
     * 字段组合校验（见类头「三种出口」）：
     * <ol>
     *   <li>{@code accepted=true} → {@code orderId} 与 {@code order} 必须都有；</li>
     *   <li>{@code accepted=false} → {@code reasonCode} 必须有，且不得带等待原因
     *       （拒绝了却告诉前端「在等货」是自相矛盾的）；</li>
     *   <li>{@code wakeEtaTs} 只在延后（{@code waitReason} 非空）时有语义——
     *       受理即调度却带唤醒时刻，会让定时唤醒器白跑一趟。</li>
     * </ol>
     */
    public OrderSubmitResponse {
        if (accepted) {
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalArgumentException("受理/延后的响应必须带订单号");
            }
            if (order == null) {
                throw new IllegalArgumentException("受理/延后的响应必须带订单视图：" + orderId);
            }
        } else {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("拒绝的响应必须带原因码");
            }
            if (waitReason != null) {
                throw new IllegalArgumentException("已拒绝的订单不应带等待原因：" + waitReason);
            }
        }
        if (wakeEtaTs != null && waitReason == null) {
            throw new IllegalArgumentException("唤醒时刻只在延后时有语义");
        }
        // LOW-MAINT.10：message 可空会让前端弹空白提示；拒绝时用原因码兜底
        if (!accepted && (message == null || message.isBlank())) {
            message = reasonCode;
        }
    }

    /**
     * 兼容 11-05 之前的 7 参构造（不带 {@code etaTs}）。
     *
     * <p>保留理由：{@code etaTs} 的来源是 {@code order_task.eta_ts} 列，而该列由张睿哲的
     * {@code 010_order_cargo_eta.sql} 新增；在迁移合入之前，本响应的取值恒为
     * {@code null}，与旧构造器等价。调用点不必为「列还没建」这件事改一遍。
     *
     * @param accepted   是否受理
     * @param orderId    订单标识
     * @param order      订单展示视图
     * @param reasonCode 原因码
     * @param message    原因说明
     * @param waitReason 延后等待原因
     * @param wakeEtaTs  预计唤醒时刻
     */
    public OrderSubmitResponse(boolean accepted, String orderId, OrderView order, String reasonCode,
            String message, String waitReason, Long wakeEtaTs) {
        this(accepted, orderId, order, reasonCode, message, waitReason, wakeEtaTs, null);
    }

    /**
     * 受理并调度。{@code etaTs} 取自订单视图（同一来源 {@code order_task.eta_ts}）。
     *
     * @param order 订单视图；<b>不得为 null</b>（MED-BUG.37：改前直接解引用，NPE 会把
     *              一次成功的下单变成 500）
     * @return 受理响应
     */
    public static OrderSubmitResponse accepted(OrderView order) {
        if (order == null) {
            throw new IllegalArgumentException("受理响应必须带订单视图");
        }
        return new OrderSubmitResponse(true, order.orderId(), order, null, null, null, null,
                order.etaTs());
    }

    /**
     * 延后待唤醒：受理通过但不进调度，等待唤醒。
     *
     * @param order      订单视图；不得为 null
     * @param reasonCode 判定链原因码
     * @param message    原因说明
     * @param waitReason 等待原因
     * @param wakeEtaTs  预计唤醒时刻
     * @return 延后响应
     */
    public static OrderSubmitResponse deferred(OrderView order, String reasonCode, String message,
            String waitReason, Long wakeEtaTs) {
        if (order == null) {
            throw new IllegalArgumentException("延后响应必须带订单视图");
        }
        return new OrderSubmitResponse(true, order.orderId(), order, reasonCode, message,
                waitReason, wakeEtaTs, order.etaTs());
    }

    /**
     * 受理拒绝（含转人工：转人工用 {@code reasonCode=INCOMPLETE} 表达）。
     *
     * @param reasonCode 原因码（不得为空，取值域见类头）
     * @param message    原因说明（为空时自动用原因码兜底）
     * @return 拒绝响应
     */
    public static OrderSubmitResponse rejected(String reasonCode, String message) {
        return new OrderSubmitResponse(false, null, null, reasonCode, message, null, null, null);
    }
}
