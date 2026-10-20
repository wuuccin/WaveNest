package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.OrderListResponse;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接入接口（M2 · 卢肄文 · 模块二功能 1）。
 *
 * <p>路径一律取 {@link ApiPath#ORDERS} 常量（{@code /api/orders}），
 * <b>不写字面量</b>：路径属契约，改动须由收口方裁决。
 *
 * <ul>
 *   <li>{@code POST /api/orders}——船员下单：接入 ＋ 基本校验，返回受理结果；</li>
 *   <li>{@code GET /api/orders}——调度后台拉订单列表，响应含 {@code dispatchGate}。</li>
 * </ul>
 *
 * <p>本类只做参数转发与响应装配：判定逻辑在 {@link OrderAdmissionService}，
 * 闸门状态的<b>判定</b>在 {@code dispatch.gate}（张睿哲），本类只<b>回显</b>其摘要。
 */
@RestController
public class OrderController {

    private final OrderAdmissionService admissionService;

    private final DispatchGateService dispatchGateService;

    public OrderController(OrderAdmissionService admissionService,
            DispatchGateService dispatchGateService) {
        this.admissionService = admissionService;
        this.dispatchGateService = dispatchGateService;
    }

    /**
     * 下单。
     *
     * <p>始终返回 200：业务拒绝由响应体的 {@code accepted=false} ＋ {@code reasonCode}
     * 表达（理由见 {@link OrderSubmitResponse}）。
     */
    @PostMapping(ApiPath.ORDERS)
    public OrderSubmitResponse submitOrder(@RequestBody OrderSubmitRequest request) {
        return admissionService.submit(request);
    }

    /**
     * 订单列表 ＋ 响应级闸门摘要。
     *
     * <p>闸门摘要取自 {@link DispatchGateService#summary()}（功能 12 的展示侧，契约
     * {@code 22 §3.2}）。<b>本类不参与闸门判定</b>——熔断／解除由 {@code gate} 子包
     * 的 {@code trip}/{@code clear} 维护，接口只负责如实回显。
     */
    @GetMapping(ApiPath.ORDERS)
    public OrderListResponse listOrders() {
        List<OrderView> orders = admissionService.listOrders();
        return new OrderListResponse(orders, dispatchGateService.summary());
    }
}
