package com.waterlogistics.contract.order;

/**
 * 已确认的订单展示状态事件。
 *
 * <p>由写 {@code order_task.status} 的模块在「状态与 {@code task_log} 同事务写入后」发布：
 * M2 的状态推进与人工干预、M4 的遥测推进都在发布侧。组装层只负责把其中的
 * {@link OrderView} 转发为 {@code order_state}，不在推送层推断订单状态。
 *
 * <h3>为什么放在 {@code wl-contract} 而不是 {@code wl-m2-dispatch}</h3>
 * <p>订单状态的写入方不止 M2：M4 的遥测握手同样把「已派发」推进到「去程中」，把
 * 「末段交接中」推进到「返航中」。而 {@code wl-m4-monitor} 只依赖
 * {@code wl-contract}／{@code wl-algo}／{@code wl-storage}，与 {@code wl-m2-dispatch}
 * 互不依赖；事件类留在 M2 时 M4 无法发布，只能让订单视图停在干预／起飞前的状态，
 * 而落库状态早已前移——界面与库的错位在演示现场无法解释。
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
