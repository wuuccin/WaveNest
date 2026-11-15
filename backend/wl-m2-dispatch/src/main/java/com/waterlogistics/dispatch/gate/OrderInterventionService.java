package com.waterlogistics.dispatch.gate;

/**
 * 订单级人工干预的执行出口（M2 · 序号 3）。
 *
 * <p>由 {@code wl-m2-dispatch} 声明、装配层提供唯一实现；{@link GateController} 与
 * {@link DispatchGateService} 只依赖本接口，单测可传 lambda，无须起 Spring 容器或 mock 框架。
 *
 * <p>实现方按《1112-功能做实批次｜接口约定》§3.3 抛
 * {@link org.springframework.web.server.ResponseStatusException}：404 订单不存在、
 * 409 订单状态不允许；不得吞异常后仍让上层返回 {@code confirmed=true}。
 *
 * @see InterventionAction 八个动作与状态准入的唯一定义处
 */
@FunctionalInterface
public interface OrderInterventionService {

    /**
     * 执行一个订单级动作。
     *
     * <p>返回值只服务《1112》§3.4 的 {@code message} 字段：{@code null} 表示动作已完全生效；
     * 非空表示动作已受理并留痕、但预期效果没有落地（目前只有 {@code adjust_meet}
     * 会面点未重算成功这一种），由 {@link DispatchGateService} 转成
     * {@code DispatchGate.withRejection} 回吐前端。状态流转类动作恒返回 {@code null}。
     *
     * @param action  归一后的英文 action（五个订单级动作之一）
     * @param orderId 已由调用方校验非空的订单号
     * @return {@code null} 或未生效原因（中文短句，非空串）
     */
    String execute(String action, String orderId);
}
