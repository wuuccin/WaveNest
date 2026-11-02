package com.waterlogistics.contract.mq;

/**
 * 投放成功回执（主题 {@link TopicName#DATA_UAV_DELIVERY}）。
 *
 * <p>本回执回答的是「货到底投没投下去」这一事实。地面调度系统不执行投放动作，
 * 只接收无人机、投放装置或平台回传的结果；没有这条回执，脱离返航只能靠超时猜测。
 *
 * <p><b>去重键</b>为 {@code taskId + uavId + ts}：同一次投放可能因链路重发到达多次，
 * 收端按该键去重，重复回执不得重复推进订单状态。
 *
 * @param taskId  任务编号，与 {@code task_log.order_id} 同口径
 * @param orderId 订单编号，回执推进订单状态时的归属键
 * @param uavId   无人机编号
 * @param ts      投放时刻，Unix 毫秒
 * @param result  投放结果：{@code success} 或 {@code failure}
 */
public record DeliveryReceipt(
        String taskId,
        String orderId,
        String uavId,
        long ts,
        String result) {

    /** 投放成功的结果取值。 */
    public static final String RESULT_SUCCESS = "success";

    /** 投放失败的结果取值。 */
    public static final String RESULT_FAILURE = "failure";

    public DeliveryReceipt {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 不能为空：回执无法归属到订单");
        }
        if (uavId == null || uavId.isBlank()) {
            throw new IllegalArgumentException("uavId 不能为空：回执无法归属到无人机");
        }
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("result 不能为空：成功与失败必须显式给出");
        }
    }

    /** 是否为投放成功回执。 */
    public boolean success() {
        return RESULT_SUCCESS.equalsIgnoreCase(result);
    }
}
