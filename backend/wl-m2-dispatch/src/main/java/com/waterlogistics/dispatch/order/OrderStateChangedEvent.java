package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;

/**
 * 已确认的订单展示状态事件。
 *
 * <p>M2 在状态与 task_log 同事务写入后发布；组装层只负责把其中的
 * {@link OrderView} 转发为 {@code order_state}，不在推送层推断订单状态。
 */
public record OrderStateChangedEvent(OrderView view) {

    public OrderStateChangedEvent {
        if (view == null) {
            throw new IllegalArgumentException("order view is required");
        }
    }
}
