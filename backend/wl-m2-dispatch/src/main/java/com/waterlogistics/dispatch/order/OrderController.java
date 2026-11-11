package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.CargoTypeOptions;
import com.waterlogistics.contract.rest.OrderListResponse;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
     *
     * <p><b>MED-BUG.33：{@code required = false} 不能省</b>。默认 {@code @RequestBody}
     * 是必填，空 body／{@code Content-Type} 缺失时 Spring 在进入方法前就抛
     * {@code HttpMessageNotReadableException}，框架回 400——于是「请求体缺失」这一种业务拒绝
     * 走的是 4xx，而其余业务拒绝都走 200 ＋ {@code reasonCode}。前端只写一套分支就会漏掉它；
     * 更糟的是 400 在监控上被归为「客户端错误」，与真正的接口故障混在一起。
     * 放开必填后，空 body 会走 {@link OrderAdmissionService.RejectReason#REQUEST_MISSING}，
     * 与其余拒绝同一口径。
     *
     * @param request 请求体（可为 {@code null}）
     * @return 受理结果（恒 200）
     */
    @PostMapping(ApiPath.ORDERS)
    public OrderSubmitResponse submitOrder(
            @RequestBody(required = false) OrderSubmitRequest request) {
        return admissionService.submit(request);
    }

    /**
     * 订单列表 ＋ 响应级闸门摘要。
     *
     * <p>闸门摘要取自 {@link DispatchGateService#summary()}（功能 12 的展示侧，契约
     * {@code 22 §3.2}）。<b>本类不参与闸门判定</b>——熔断／解除由 {@code gate} 子包
     * 的 {@code trip}/{@code clear} 维护，接口只负责如实回显。
     *
     * <p>11-05 序号 2 · LOW-PERF.1：支持按状态过滤与条数上限，参数语义见
     * {@link OrderAdmissionService#listOrders(String, Integer)}。两个参数都可省略，
     * 省略时行为与改前一致（只是默认只回 200 条）。
     *
     * @param status 状态过滤（中文状态词或枚举名），可空
     * @param limit  条数上限，可空（默认 200，最大 1000）
     * @return 订单列表 ＋ 闸门摘要
     */
    @GetMapping(ApiPath.ORDERS)
    public OrderListResponse listOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<OrderView> orders = admissionService.listOrders(status, limit);
        return new OrderListResponse(orders, dispatchGateService.summary());
    }

    /**
     * 无参重载：等同 {@code status=null, limit=null}（默认上限 200 条）。
     *
     * <p><b>不带 {@code @GetMapping}</b>，因此不会注册成第二个 handler——Spring 的
     * 请求映射只认带注解的方法，同路径两个 handler 会在启动期报
     * {@code Ambiguous mapping}。保留它只为让直接调用本类（单测、旁路脚本）的代码
     * 不必因为新增两个查询参数而改签名。
     *
     * @return 订单列表 ＋ 闸门摘要
     */
    public OrderListResponse listOrders() {
        return listOrders(null, null);
    }

    /**
     * 当前可承运配送品类字典。
     *
     * <p>本接口只返回接单判定实际使用的有效集合，不返回契约默认全集，避免前端
     * 下拉出现能选却一定被拒的选项。列表顺序与拒单提示一致。
     *
     * @return 按固定顺序排列的可承运品类
     */
    @GetMapping(ApiPath.CARGO_TYPES)
    public CargoTypeOptions cargoTypes() {
        return new CargoTypeOptions(admissionService.cargoTypeOptions());
    }

    /**
     * 货物到巢登记（幂等）。
     *
     * <p><b>为什么是 POST 而不是 PATCH</b>：语义是「报告一个已经发生的物理事实（货到了）」，
     * 不是「把资源改成某个目标状态」。事实可以被重复报告，正好对上本端点的幂等口径。
     *
     * <p><b>为什么请求体必须带货巢编号</b>：登记要核对货实际到达的巢与订单记的货巢，
     * 缺巢编号就核不了，「货到了 A 巢、订单记的是 B 巢」这类错位会溜过去。
     * 请求体缺失或巢编号空白都按业务拒绝处理（{@code accepted=false} ＋ 原因码），
     * 不按接口故障处理。
     *
     * <p>始终返回 200：查无货物／状态不可回退由响应体的 {@code accepted=false} ＋
     * {@code reasonCode} 表达，与 {@link OrderSubmitResponse} 的口径一致
     * （避免前端把业务拒绝当成接口故障）。
     */
    @PostMapping(ApiPath.CARGO_READY_TEMPLATE)
    public CargoReadyResponse cargoReady(@PathVariable("orderId") String orderId,
            @RequestBody(required = false) CargoReadyRequest request) {
        return cargoReadyService.markReady(orderId, request == null ? null : request.cargoNestId());
    }
}
