package com.waterlogistics.dispatch.prediction;

/**
 * 重算请求的承接出口（M2 · 卢肄文）。
 *
 * <p>为什么用接口而不是直接调 M3：{@code RendezvousFacade} 的 Bean 由 {@code wl-app} 装配
 * （其构造器要 {@code TrajectorySnapshotPort} 与 {@code DroneContextPort} 两个实现），
 * 在装配完成前，本模块若直接依赖门面类型会**启动即缺 Bean**。
 * 定义成本模块自己的出口后：
 * <ul>
 *   <li>本模块可独立编译、独立单测、独立启动；</li>
 *   <li>{@code wl-app} 装配好门面后，提供一个门面实现替换默认实现即可（改动被关在装配层）；</li>
 *   <li>依赖方向仍满足《包结构设计》§二：M2 调 M3 只经门面，<b>不碰 solver 内部类</b>。</li>
 * </ul>
 *
 * <p>当前默认实现见 {@link LoggingRecomputeRequester}（只留痕，不真调求解）。
 */
public interface RecomputeRequester {

    /**
     * 发出一次重算请求。
     *
     * <p>实现方<b>不得抛异常给调用方</b>：本方法在预测刷新的热路径上被调用，
     * 下游（M3 求解、任务上下文）不可用不应影响预测链路本身。
     *
     * @param request 重算信号
     */
    void requestRecompute(RecomputeRequest request);
}
