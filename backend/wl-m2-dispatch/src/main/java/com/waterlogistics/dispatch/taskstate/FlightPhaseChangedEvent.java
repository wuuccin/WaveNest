package com.waterlogistics.dispatch.taskstate;

import com.waterlogistics.contract.order.OrderView;

/**
 * 飞行阶段变化事件——飞行态迁移写库后广播，由组装层转成 {@code order_state}。
 *
 * <p>与 {@code OrderStateChangedEvent} 分开：后者只表达订单态变化，
 * 本事件表达 {@code task_log} 中的飞行态迁移；两者共用 {@link OrderView} 投影，
 * 前端不需要两套渲染结构。
 *
 * @param view 飞行态展示视图
 */
public record FlightPhaseChangedEvent(OrderView view) {

    public FlightPhaseChangedEvent {
        if (view == null) {
            throw new IllegalArgumentException("飞行阶段视图不能为空");
        }
    }
}
