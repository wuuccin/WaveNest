package com.waterlogistics.dispatch.order;

import java.math.BigDecimal;

/**
 * 订单受理事件（B0 主链触发器的入口信号）。
 *
 * <p>{@link OrderAdmissionService#submit} 建单成功后发布本事件，
 * {@code dispatch.queue.DispatchTrigger} 监听后驱动
 * 「落库 order_task → 受理 → 组批调度」，使订单自动从「待受理」推进到「调度中」，
 * 不再卡在「待受理」。
 *
 * @param orderId         订单号
 * @param shipMmsi        目标船 MMSI
 * @param goodsWeightKg   物资载重，kg
 * @param goodsType       物资类型（可空）
 * @param expectedTimeMs  期望送达时刻，Unix 毫秒（可空）
 * @param priority        优先级（NORMAL/URGENT/EMERGENCY）
 * @param submitTsMs      下单时刻，Unix 毫秒
 */
public record OrderSubmittedEvent(
        String orderId,
        String shipMmsi,
        Double goodsWeightKg,
        String goodsType,
        Long expectedTimeMs,
        String priority,
        long submitTsMs) {

    /** 载重转 BigDecimal（落库用）；null 安全。 */
    public BigDecimal goodsWeightDecimal() {
        return goodsWeightKg == null ? null : BigDecimal.valueOf(goodsWeightKg);
    }
}
