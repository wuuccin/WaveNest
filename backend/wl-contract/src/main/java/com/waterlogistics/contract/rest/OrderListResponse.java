package com.waterlogistics.contract.rest;

import com.waterlogistics.contract.order.OrderView;
import java.util.List;

/**
 * {@code GET /api/orders} 的响应体——依据《22-接口契约》v1.2 §3、§3.1、§3.2。
 *
 * @param orders       订单列表，每项为 {@link OrderView}
 * @param dispatchGate 响应级闸门摘要（{@code gateStatus}／{@code gateSources}）
 */
public record OrderListResponse(List<OrderView> orders, DispatchGate dispatchGate) {
}
