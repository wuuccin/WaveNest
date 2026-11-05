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
 * <p><b>装配规则（LOW.23，防 {@code NoUniqueBeanDefinitionException}）</b>：容器内允许存在多个实现，
 * 但注入点必须唯一确定——业务实现标注 {@code @Primary}，默认实现 {@link LoggingRecomputeRequester}
 * 保持 {@code @Component} 作兜底（不排除）。注入点按 {@code @Primary} 选业务实现；容器无业务实现时
 * 自然落到默认实现。新增实现方必须遵守本规则，并同步更新本段说明。
 *
 * <p><b>MEDIUM.3 更正（本段曾经过期）</b>：默认实现早已不是「只留痕」。
 * {@link LoggingRecomputeRequester#requestRecompute} 会调用
 * {@code PredictionService#recompute(mmsi)} 真算一版并广播 {@code ship_traj}
 * （F3「重算链接通」的落点），留痕只是它顺带的副作用。真正的门面实现见
 * {@code wl-app} 的 {@code RendezvousRecomputeRequester}——它带 {@code @Primary}，
 * 生产环境走它；本接口由 M2 声明，正是为了让 M2 在门面尚未装配时也能独立编译与启动。
 */
public interface RecomputeRequester {

    /**
     * 发出一次重算请求——<b>实现方的唯一抽象方法</b>。
     *
     * <p><b>实现方不得向调用方抛异常</b>：本方法在预测刷新的热路径上被调用，
     * 下游（M3 求解、任务上下文、事件广播）不可用不应影响预测链路本身。
     * 异常须在实现内部收敛为日志。这条约束不是靠自觉：
     * {@link #notifyRecompute} 是唯一的对外调用口，它已把实现体包进 try/catch
     * （含 {@link RuntimeException} 与 {@link Error} 之外的受检异常无法从本签名逃逸），
     * 调用方（{@link PredictionService}）只调它。
     *
     * <p><b>线程安全与阻塞</b>：本方法可能被多条船的输入线程并发调用，实现方必须线程安全，
     * 且<b>不应长时间阻塞</b>（同步做完整重算可以，但要清楚这会把耗时叠到调用方线程上；
     * 更稳的做法是投递到有界队列由专用线程执行）。
     *
     * <p><b>幂等</b>：同一 {@code mmsi} 的重复或过期版本信号允许被合并／丢弃，
     * 实现方按 {@code snapshotVersion} 判新旧即可，不必保证「每条信号都产生一版快照」。
     *
     * @param request 重算信号，<b>非 null</b>（调用方保证；实现仍应容错）
     */
    void requestRecompute(RecomputeRequest request);

    /**
     * 对外唯一调用口：把 {@link #requestRecompute} 包成<b>绝不外抛</b>的形式（MEDIUM.12）。
     *
     * <p>为什么把防护放在接口而放在调用方：接口 javadoc 写「不得抛异常」只是散文，
     * 每个调用点都要自己写 try/catch，漏一处就把异常送进 AIS 消费线程；
     * 而实现方一旦违反约定（未来的门面实现要跨模块调 M3，最容易在这里抛），
     * 破坏会直接体现在预测链上。做成 default 方法后，「不抛」由类型系统保证，
     * 新增实现无需重复实现这段防护。
     *
     * <p>吞异常时记 warn 并带上异常对象：热路径吞异常可以，但必须留下可定位的栈。
     *
     * @param request 重算信号；为 {@code null} 时直接忽略（调用方缺陷，记 warn 不抛）
     */
    default void notifyRecompute(RecomputeRequest request) {
        if (request == null) {
            return;
        }
        try {
            requestRecompute(request);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(RecomputeRequester.class)
                    .warn("重算请求处理失败，已忽略（实现方违反『不得抛异常』约定）：mmsi={} 版本={}",
                            request.mmsi(), request.snapshotVersion(), e);
        }
    }
}
