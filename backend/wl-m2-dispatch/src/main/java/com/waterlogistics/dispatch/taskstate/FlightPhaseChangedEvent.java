package com.waterlogistics.dispatch.taskstate;

import com.waterlogistics.contract.order.OrderView;

/**
 * 飞行阶段变化事件——飞行态迁移写库后广播，由组装层转成 {@code order_state}。
 *
 * <p>与 {@code OrderStateChangedEvent} 分开：后者只表达订单态变化，
 * 本事件表达 {@code task_log} 中的飞行态迁移；两者共用 {@link OrderView} 投影，
 * 前端不需要两套渲染结构。
 *
 * <h3>载荷语义（MEDIUM.17）</h3>
 *
 * <p>两者共用 {@link OrderView} 不等于两者填得一样满。本事件的唯一发布方
 * {@code FlightPhaseAdvancer} 只填六个字段：
 * {@code orderId / orderStatus（到态）/ eventTs / stateVersion / etaTs / cargoNestId}。
 * 其余四个<b>恒为 {@code null}</b>：
 * <ul>
 *   <li>{@code waitReason}——飞行中的订单不存在等待唤醒，该字段只属「已受理待唤醒」；</li>
 *   <li>{@code wakeEtaTs}——同上，飞行中不预估唤醒时刻；</li>
 *   <li>{@code emergencyLandingPoint}——迫降点只在失败终态由 {@code HomecomingService} 写，
 *       飞行阶段推进时还没有这个事实；</li>
 *   <li>{@code handoverPhase}——末段子阶段由航迹侧单独广播，不经本事件。</li>
 * </ul>
 *
 * <p><b>消费方必须按「字段级合并」处理</b>：收到本事件时只能覆盖上述六个字段，
 * 不得用事件里的 {@code null} 去清空前端已有的 {@code waitReason} 与 {@code wakeEtaTs}
 * ——那是订单态事件的内容，本事件无从表达「已清空」。
 *
 * <p><b>{@code stateVersion} 口径与订单态事件不同源</b>：本事件取
 * {@code max(2, task_log.log_id + 1)}（该行 logId 为空时退回进程内递增兜底），
 * 而订单态事件取 {@code order_task.state_version}。两个序号各自递增、不可直接比大小后合并，
 * 消费方不能拿本事件的版本去覆盖或拒收订单态消息。
 *
 * @param view 飞行态展示视图；{@code orderId} 与 {@code orderStatus} 由发布方保证非空，
 *             其余字段可空，含义见上
 */
public record FlightPhaseChangedEvent(OrderView view) {

    public FlightPhaseChangedEvent {
        // LOW.12 维持缓置：不在此校验 view.orderId() 与 view.orderStatus()。
        // 理由——本事件的构造点全仓只有一处（FlightPhaseAdvancer 的广播块），两字段由
        // 库行主键与状态迁移目标直接给出，不存在外部传入的「半填充视图」；而广播块已用
        // try/catch 把异常降级为「推送失败只告警」，在这一层再抛只会被吞掉，
        // 既暴露不了缺陷，又会让一条审计已落库的迁移少推一帧。等出现第二个发布方时再补校验。
        if (view == null) {
            throw new IllegalArgumentException("飞行阶段视图不能为空");
        }
    }
}
