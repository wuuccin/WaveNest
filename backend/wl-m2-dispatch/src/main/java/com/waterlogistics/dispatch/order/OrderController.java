package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.OrderListResponse;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接入接口（M2 · 卢肄文 · 模块二功能 1）。
 *
 * <p>路径一律取 {@link ApiPath} 常量，<b>不写字面量</b>：路径属契约，改动须由收口方裁决。
 *
 * <ul>
 *   <li>{@code POST /api/orders}——船员下单：接入 ＋ 基本校验，返回受理结果；</li>
 *   <li>{@code GET /api/orders}——调度后台拉订单列表，响应含 {@code dispatchGate}；</li>
 *   <li>{@code POST /api/orders/{orderId}/cargo-ready}——货物到巢登记（幂等）。</li>
 * </ul>
 *
 * <p>本类只做参数转发与响应装配：判定逻辑在 {@link OrderAdmissionService}，
 * 货物就绪登记在 {@link CargoReadyService}，闸门状态的<b>判定</b>在 {@code dispatch.gate}
 * （张睿哲），本类只<b>回显</b>其摘要。
 */
@RestController
public class OrderController {

    private final OrderAdmissionService admissionService;

    private final DispatchGateService dispatchGateService;

    private final CargoReadyService cargoReadyService;

    public OrderController(OrderAdmissionService admissionService,
            DispatchGateService dispatchGateService,
            CargoReadyService cargoReadyService) {
        this.admissionService = admissionService;
        this.dispatchGateService = dispatchGateService;
        this.cargoReadyService = cargoReadyService;
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

    /**
     * 货物到巢登记（幂等）。
     *
     * <p><b>为什么是 POST 而不是 PATCH</b>：语义是「报告一个已经发生的物理事实（货到了）」，
     * 不是「把资源改成某个目标状态」。事实可以被重复报告，正好对上本端点的幂等口径。
     *
     * <p>始终返回 200：查无货物／状态不可回退由响应体的 {@code accepted=false} ＋
     * {@code reasonCode} 表达，与 {@link OrderSubmitResponse} 的口径一致
     * （避免前端把业务拒绝当成接口故障）。
     */
    @PostMapping(ApiPath.CARGO_READY_TEMPLATE)
    public CargoReadyResponse cargoReady(@PathVariable("orderId") String orderId) {
        return cargoReadyService.markReady(orderId);
    }
}
