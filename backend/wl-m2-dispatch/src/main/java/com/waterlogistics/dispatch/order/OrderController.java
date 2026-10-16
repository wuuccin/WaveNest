package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.rest.OrderListResponse;
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
 * <p>本类只做参数转发与响应装配，判定逻辑在 {@link OrderAdmissionService}。
 */
@RestController
public class OrderController {

    private final OrderAdmissionService admissionService;

    public OrderController(OrderAdmissionService admissionService) {
        this.admissionService = admissionService;
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

    /** 订单列表 ＋ 响应级闸门摘要。 */
    @GetMapping(ApiPath.ORDERS)
    public OrderListResponse listOrders() {
        List<OrderView> orders = admissionService.listOrders();
        return new OrderListResponse(orders, currentDispatchGate());
    }

    /**
     * 当前派单闸门摘要。
     *
     * <p><b>★ 临时实现</b>：闸门的真实状态由 M2 的 {@code gate} 子包
     * （张睿哲：{@code gateStatus}/{@code gateSources} 回显、{@code /api/intervene}）提供，
     * 今天尚未落地，故此处在<b>本控制器内</b>回一个「正常」，<b>不</b>自造闸门判定逻辑
     * （判定归 {@code gate} 子包，本类不越界）。待闸门服务可用后改为注入取用。
     */
    private DispatchGate currentDispatchGate() {
        return new DispatchGate(GateStatus.NORMAL, List.of());
    }
}
