package com.waterlogistics.dispatch.reschedule;

/**
 * 订单重入队的对外出口（M2 消费方定义的端口，实现由 {@code dispatch.queue} 提供方给出）。
 *
 * <p><b>为什么要定端口而不是直接依赖实现类</b>：《42》§2 依赖方向与《16 号清单》§3.7
 * 「谁提供能力谁定义接口、互相只依赖接口不 import 实现类」的口径下，M2 要让
 * 「订单状态推进 ＋ {@code task_log} 落库」落到 {@code dispatch.queue}（张睿哲归口，
 * 子包粒度不归 M2 本线），就不能跨包改它的类、也不能 {@code import} 它的实现。
 * 故这里由消费方声明所需能力，{@code wl-app} 装配时注入提供方的实现。
 *
 * <p><b>本接口只声明 16 号闭环真正用到的两项</b>：状态推进与时间线追加。
 * 派单重选（张睿哲「重派 ＋ 候选择优」）不在本端口内——那是订单回到「待调度」<b>之后</b>
 * 的调度动作，不属于「重入队」这一步。
 */
public interface OrderRequeuePort {

    /**
     * 执行重入队：推进订单状态、写事件流，并把节点追加到订单状态时间线。
     *
     * <p>写入方口径照《23》§8「决策归 M2、落库归 M6」：本方法所在实现负责决策与事务边界，
     * 落库由 M6 的 Mapper 完成，<b>订单与任务同事务</b>（《23》§5 约束 2）。
     *
     * <p>时间线口径照《22》§4 {@code order_state} 帧：只有 {@code orderStatus} 改变才追加
     * 节点，<b>仅展示数据变化不重复记流转</b>。
     *
     * @param cmd        重入队入参
     * @param fromStatus 变更前订单状态（中文状态词，照 {@code OrderStatus.getValue()}）
     * @param toStatus   变更后订单状态（中文状态词）
     * @return 重入队结果（订单标识、货物状态、时间线是否已追加）
     */
    RequeueResult requeue(OrderRequeueCommand cmd, String fromStatus, String toStatus);

    /**
     * 重入队结果。
     *
     * @param orderId          订单标识
     * @param goodsStatus      货物落库状态（中文状态词，{@code GoodsStatus.getValue()}）
     * @param timelineAppended 是否已向订单状态时间线追加节点
     */
    record RequeueResult(String orderId, String goodsStatus, boolean timelineAppended) {

        public RequeueResult {
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalArgumentException("重入队结果缺订单标识");
            }
            if (goodsStatus == null || goodsStatus.isBlank()) {
                throw new IllegalArgumentException(
                        "重入队结果缺货物状态：货物须已回收才构成重派入口（《21》§8）");
            }
        }
    }
}