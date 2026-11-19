package com.waterlogistics.dispatch.order;

/**
 * {@code POST /api/orders/{orderId}/resource-release} 的响应体（M2 · 张睿哲 · 11-19 序号 1-2）。
 *
 * <p>人工受控动作的唯一语义是：把「已受理待唤醒 ＋ 等待原因＝等待资源」的订单
 * <b>送回待调度</b>，由派单触发器重新取单；本接口<b>不</b>占用时隙、<b>不</b>直接派单。
 *
 * <p><b>为什么把「是否真的释放了」单独成字段</b>：前端处置按钮可能与定时重试、
 * 运维手动调用并发，重复点击不应二次写 {@code task_log}。{@code released=false} 时
 * {@code status} 回显订单当前状态（不是待调度的单不迁移），供前端提示原因。
 *
 * @param released 本次是否完成迁移（{@code true} ＝ 已回到待调度）
 * @param orderId  订单标识（回显归一后的值）
 * @param status   迁移后的订单状态；拒绝时为订单当前状态（查无订单时为 {@code null}）
 * @param message  中文说明，供前端直接展示
 */
public record ResourceReleaseResponse(
        boolean released,
        String orderId,
        String status,
        String message) {

    /** 释放成功：订单已回到待调度。 */
    public static ResourceReleaseResponse released(String orderId, String status) {
        return new ResourceReleaseResponse(true, orderId, status, "已释放资源等待，订单回到待调度");
    }

    /** 拒绝：不满足释放条件，订单状态未迁移。 */
    public static ResourceReleaseResponse rejected(String orderId, String status, String message) {
        return new ResourceReleaseResponse(false, orderId, status, message);
    }
}
