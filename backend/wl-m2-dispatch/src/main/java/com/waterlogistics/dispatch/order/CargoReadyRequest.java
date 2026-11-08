package com.waterlogistics.dispatch.order;

/**
 * {@code POST /api/orders/{orderId}/cargo-ready} 的请求体。
 *
 * <p><b>为什么货巢编号必填</b>：登记的是「货到了哪个巢」这个已经发生的物理事实，
 * 没有巢编号就无法与订单记的货巢比对，「货到了 A 巢、订单记的是 B 巢」这类错位
 * 就拦不住。因此缺失巢编号一律拒绝，不做「缺了就当按订单的算」这种宽容处理。
 *
 * @param cargoNestId 货物实际到达的巢编号；空白视为缺失
 */
public record CargoReadyRequest(String cargoNestId) {
}
