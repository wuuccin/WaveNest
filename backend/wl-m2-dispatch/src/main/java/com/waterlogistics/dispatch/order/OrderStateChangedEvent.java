package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;

/**
 * 已确认的订单展示状态事件。
 *
 * <p>M2 在状态与 task_log 同事务写入后发布；组装层只负责把其中的
 * {@link OrderView} 转发为 {@code order_state}，不在推送层推断订单状态。
 *
 * <h3>11-05 序号 2 · LOW-MAINT.9：不只校验 view 非空</h3>
 * <p>{@code view != null} 只挡住「什么都没有」，挡不住「有个空壳」：
 * {@code orderId} 为空的帧推到前端后无法路由到任何订单卡片（按 {@code orderId} 建索引），
 * {@code orderStatus} 为空的帧会让前端状态机读到 {@code undefined} 并停在旧状态。
 * 两者都不是「少显示一个字段」，而是<b>在消费端制造一个不可恢复的错误态</b>，
 * 且发生在推送链路上——推送失败不会有人看见异常，只会表现为「前端不动了」。
 * 因此在发布侧就拒，让问题停在产生它的地方。
 */
public record OrderStateChangedEvent(OrderView view) {

    public OrderStateChangedEvent {
        if (view == null) {
            throw new IllegalArgumentException("order view is required");
        }
        if (view.orderId() == null || view.orderId().isBlank()) {
            throw new IllegalArgumentException("order view 缺少 orderId，推送后前端无法路由");
        }
        if (view.orderStatus() == null) {
            throw new IllegalArgumentException(
                    "order view 缺少 orderStatus，前端状态机会读到 undefined：" + view.orderId());
        }
    }
}
