package com.waterlogistics.dispatch.order;

/**
 * {@code POST /api/orders} 的请求体（M2 · 卢肄文）。
 *
 * <p>字段对齐 {@code order_task} 的权威列名（{@code 37} §5.4，与
 * {@code deploy/sql/001_init.sql} 一字不改）：{@code ship_mmsi}／{@code goods_weight}／
 * {@code goods_type}／{@code expected_time}／{@code priority}。
 *
 * <p>契约 {@code wl-contract} 只定义了<b>响应</b>对象（{@link
 * com.waterlogistics.contract.order.OrderView}、{@link
 * com.waterlogistics.contract.rest.OrderListResponse}），未定义请求体，
 * 故本记录体放在模块层（M2 的 {@code order} 子包）。字段名不得偏离上表。
 *
 * <p><b>不由客户端提交的字段</b>：{@code ship_lon}／{@code ship_lat} 是「下单时目标船位」，
 * 应由服务端自行掌握（今天未落库，落库时由 M6 在写入前补），不由船员端指定。
 *
 * @param shipMmsi      目标船 MMSI，9 位数字（必填）
 * @param goodsWeight   物资载重，kg（必填，须为正）
 * @param goodsType     物资类型（可空）
 * @param expectedTimeMs 期望送达时间，Unix 毫秒（可空；填了必须晚于当前时刻）
 * @param priority      优先级 {@code NORMAL/URGENT/EMERGENCY}（可空，缺省 NORMAL）
 */
public record OrderSubmitRequest(
        String shipMmsi,
        Double goodsWeight,
        String goodsType,
        Long expectedTimeMs,
        String priority) {
}
