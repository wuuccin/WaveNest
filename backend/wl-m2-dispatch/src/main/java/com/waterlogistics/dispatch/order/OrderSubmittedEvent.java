package com.waterlogistics.dispatch.order;

import java.math.BigDecimal;

/**
 * 订单受理事件（B0 主链触发器的入口信号）。
 *
 * <p>{@link OrderAdmissionService#submit} 判定出口为「受理并调度」或「延后待唤醒」时发布本事件，
 * {@code dispatch.queue.DispatchTrigger} 监听后驱动落库；前者继续
 * 「受理 → 组批调度」，后者只落库为「已受理待唤醒」、不进调度
 * （阶段一 26 接线：接单判定不通过的延后单也要在 {@code order_task} 可见）。
 *
 * @param orderId         订单号
 * @param shipMmsi        目标船 MMSI
 * @param goodsWeightKg   物资载重，kg
 * @param goodsType       物资类型（可空）
 * @param expectedTimeMs  期望送达时刻，Unix 毫秒（可空）
 * @param priority        优先级（NORMAL/URGENT/EMERGENCY）
 * @param submitTsMs      下单时刻，Unix 毫秒
 * @param waitReason      延后等待原因（{@code WAIT_SHIP}／{@code WAIT_CARGO}／{@code GATE_OR_DATA}）；
 *                        {@code null} 表示受理并调度，进完整主链
 * @param wakeEtaTs       预计唤醒时刻，Unix 毫秒；不可估计为 {@code null}（仅延后有语义）
 * @param etaTs           预计送达时刻，Unix 毫秒；未算出为 {@code null}。
 *                        11-05 序号 2 新增：由 {@code AdmissionPipeline.Decision.etaTsMs} 给出，
 *                        {@code DispatchTrigger} 落 {@code order_task.eta_ts}。
 *                        与 {@code wakeEtaTs}（预计唤醒）不同义，不得互赋
 */
public record OrderSubmittedEvent(
        String orderId,
        String shipMmsi,
        Double goodsWeightKg,
        String goodsType,
        Long expectedTimeMs,
        String priority,
        long submitTsMs,
        String waitReason,
        Long wakeEtaTs,
        Long etaTs) {

    /**
     * 兼容 11-05 序号 2 之前的 9 参构造：预计送达时刻未算出。
     *
     * @param orderId        订单号
     * @param shipMmsi       目标船 MMSI
     * @param goodsWeightKg  物资载重，kg
     * @param goodsType      物资类型
     * @param expectedTimeMs 期望送达时刻，Unix 毫秒
     * @param priority       优先级
     * @param submitTsMs     下单时刻，Unix 毫秒
     * @param waitReason     延后等待原因
     * @param wakeEtaTs      预计唤醒时刻，Unix 毫秒
     */
    public OrderSubmittedEvent(String orderId, String shipMmsi, Double goodsWeightKg,
            String goodsType, Long expectedTimeMs, String priority, long submitTsMs,
            String waitReason, Long wakeEtaTs) {
        this(orderId, shipMmsi, goodsWeightKg, goodsType, expectedTimeMs, priority, submitTsMs,
                waitReason, wakeEtaTs, null);
    }

    /** 载重转 BigDecimal（落库用）；null 安全。 */
    public BigDecimal goodsWeightDecimal() {
        return goodsWeightKg == null ? null : BigDecimal.valueOf(goodsWeightKg);
    }

    /** 是否延后单（延后单落库即「已受理待唤醒」，不进调度）。 */
    public boolean deferred() {
        return waitReason != null;
    }
}
