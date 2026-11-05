package com.waterlogistics.algo.estimation;

import java.util.List;

/**
 * 系统送达截止时刻 {@code deadline}——「最晚可以什么时候送到」，不读时钟以外的一切外部状态。
 *
 * <h3>口径</h3>
 * <pre>
 *   deadline = min( 船驶出服务航段的时刻 , 下单时刻 + maxDeliverySec )
 * </pre>
 * <p><b>为什么取两者的较小值</b>：任务的服务对象是「在航船舶」——船驶出服务航段后，
 * 会面已不可能，再晚的时限也是空头承诺；而 {@code maxDeliverySec} 是运维给的业务上限，
 * 防止船长期停在段内导致时限被无限拉长。两者都是"硬边界"，取小即同时满足。
 *
 * <p><b>为什么船驶出时刻可能算不出来</b>：预测时域有限，船可能在时域之外才驶出；
 * 此时驶出时刻不可得，<b>不得</b>当成"永远不驶出"去给一个无限远的时限，
 * 退化为 {@code 下单时刻 + maxDeliverySec}。这样得到的 deadline 只会更宽松，
 * 不会凭空拒单——宽松的后果是后续由 {@code etaMin} 判据兜住，而不是在这里误杀。
 *
 * <p><b>与 {@code expected_time} 的关系</b>：本类的输出就是落 {@code order_task.expected_time}
 * 的值（列名沿用旧语义，值改为系统算出）。客户端若仍提交期望送达时间，按更早者取——
 * 那是用户给自己定的更严约束，不覆盖系统算出的硬边界。
 */
public final class DeliveryDeadline {

    /**
     * 时限上限（秒）不得超过一天。
     *
     * <p>用途是挡住单位写错：把毫秒当秒填（如 {@code 1800000}）会让 deadline 落到几周之后，
     * 表现为"时限形同虚设"，而这种错误在日志里看不出来。
     */
    private static final long MAX_SEC = 86_400L;

    private DeliveryDeadline() {
    }

    /**
     * 计算系统 deadline。
     *
     * @param nowMs          下单时刻，Unix 毫秒
     * @param shipExitTsMs   船驶出服务航段的时刻，Unix 毫秒；预测时域内不驶出或无预测时为
     *                       {@code null}
     * @param maxDeliverySec 最大配送时限，秒（须在 {@code (0, 86400]} 内）
     * @return 系统 deadline，Unix 毫秒
     * @throws IllegalArgumentException 时限非法时抛出
     */
    public static long compute(long nowMs, Long shipExitTsMs, long maxDeliverySec) {
        if (maxDeliverySec <= 0 || maxDeliverySec > MAX_SEC) {
            throw new IllegalArgumentException(
                    "最大配送时限须在 (0, " + MAX_SEC + "] 秒内，实际 " + maxDeliverySec);
        }
        long capTsMs = nowMs + maxDeliverySec * 1000L;
        if (shipExitTsMs == null) {
            return capTsMs;
        }
        return Math.min(shipExitTsMs, capTsMs);
    }

    /**
     * 从预测点列求船驶出服务航段的时刻。
     *
     * <p>预测点列缺失、为空、或时域内不驶出时返回 {@code null}，由调用方退化为
     * 「下单时刻 + maxDeliverySec」。<b>不抛异常</b>：dealine 是每个订单都要算的量，
     * 缺预测属于常态（新船、数据断流），不该让下单失败。
     *
     * @param points   预测点列，可空
     * @param geometry 航道几何，可空
     * @param sStart   服务航段起点弧长，m
     * @param sEnd     服务航段终点弧长，m
     * @return 驶出时刻，Unix 毫秒；不可得时为 {@code null}
     */
    public static Long shipExitTsMs(List<EstimatedPoint> points, WaterwayGeometry geometry,
            double sStart, double sEnd) {
        if (points == null || points.isEmpty() || geometry == null) {
            return null;
        }
        ServiceSegmentGate.ExitResult exit = ServiceSegmentGate.firstExit(points, geometry,
                sStart, sEnd);
        return exit.exits() ? exit.exitTsMs() : null;
    }
}
