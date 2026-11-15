package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.InterveneRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 人工干预派单闸门端点（D2 · 2026-10-22；序号 3 扩到八个动作）。
 *
 * <p>路径复用契约常量 {@link ApiPath#INTERVENE}（{@code /api/intervene}），
 * <b>不写字面量</b>。每次操作后返回最新 {@link DispatchGate} 摘要并带确认字段，
 * 让调用方一次调用拿到最终状态（无须再 GET /api/orders 确认）。
 *
 * <p>八个动作见《1112-功能做实批次｜接口约定》§3.2，映射表唯一定义处为
 * {@link InterventionAction}：
 * <ul>
 *   <li>闸门级 {@code trip}／{@code clear}／{@code reset} —— 走 {@link DispatchGateService}；</li>
 *   <li>订单级 {@code adjust_meet}／{@code withdraw}／{@code cancel}／{@code abort}／
 *       {@code manual} —— 必填 {@code orderId}，经 {@link DispatchGateService} 委托
 *       {@link OrderInterventionService}，404／409 由实现方按 §3.3 抛出。</li>
 * </ul>
 *
 * <h3>错误口径（HIGH.13 ／《1112》§3.3）</h3>
 * 客户端错误（缺 action、未知 action、缺 orderId、非 MANUAL 来源）一律抛
 * {@link ResponseStatusException}（HTTP 400）；订单不存在 404、状态不允许 409。
 * 全仓没有 {@code @RestControllerAdvice}，历史实现抛 {@code IllegalArgumentException}
 * 会退化成 500 + 通用错误体。
 *
 * <h3>安全边界（HIGH.1）</h3>
 * 契约《22》§7.1 要求 {@code /api/intervene} 走调度员角色 JWT；本仓库尚未引入
 * Spring Security，<b>JWT 鉴权待接入</b>。当前以最小防护替代：接口只能操作
 * {@code MANUAL} 来源（不能伪造/清除 {@code AUTO} 气象熔断），且 {@code reset} 保留
 * {@code AUTO}——匿名调用最多冻结／解除人工冻结，无法在恶劣气象下放开派单。
 *
 * <p><b>部署边界（本批明确，不外推）</b>：本端点<b>无鉴权，仅限内网演示环境</b>使用。
 * 对外或联网部署前必须补齐 JWT 与调度员角色校验，否则任何匿名调用方都能冻结或解除派单。
 *
 * <p><b>本批不做的两件事</b>：一是不引入权限体系（台账该条原文本就写「本批不做权限体系改造」）；
 * 二是不在本层加来源白名单——允许来源由实时通道配置统一落地（《11-14》2-10 彭紫情），
 * 两处各写一套会出现「控制器放行、通道拒绝」的分歧。
 *
 * <p>边界：干预只操作<b>派单闸门与订单</b>这一层，不扩到「飞行中换机」——
 * 飞行中不换机是本项目的既定口径（回收后重派），本端点不得与之矛盾。
 */
@RestController
public class GateController {

    private final DispatchGateService dispatchGateService;

    public GateController(DispatchGateService dispatchGateService) {
        this.dispatchGateService = dispatchGateService;
    }

    /**
     * 人工干预闸门或订单。
     *
     * @throws ResponseStatusException 400 缺 action／未知 action／缺 orderId／非 MANUAL 来源；
     *                                 404 订单不存在；409 订单状态不允许该操作
     */
    @PostMapping(ApiPath.INTERVENE)
    public DispatchGate intervene(@RequestBody InterveneRequest request) {
        String action = normalizeAction(request);
        InterventionAction spec = InterventionAction.fromNormalized(action);
        if (spec == null) {
            throw badRequest("未知干预操作：" + request.action()
                    + "；支持 " + InterventionAction.SUPPORTED);
        }

        GateSource source = requireManualSource(request, action);
        String orderId = trimToNull(request.orderId());
        if (spec.requiresOrderId() && orderId == null) {
            throw badRequest("该操作需要订单号");
        }

        String rejection = null;
        switch (action) {
            case "trip" -> dispatchGateService.trip(source);
            case "clear" -> dispatchGateService.clear(source);
            case "reset" -> dispatchGateService.reset();
            case "adjust_meet" -> rejection = dispatchGateService.adjustMeet(orderId);
            case "withdraw" -> dispatchGateService.withdraw(orderId);
            case "cancel" -> dispatchGateService.cancel(orderId);
            case "abort" -> dispatchGateService.emergencyAbort(orderId);
            case "manual" -> dispatchGateService.manualTakeover(orderId);
            default -> throw badRequest("未知干预操作：" + request.action()
                    + "；支持 " + InterventionAction.SUPPORTED);
        }
        // 调整会面点按最新预测重算：未生效（无解／未派单／已进决策窗口／重算失败）时
        // 仍返回 200（动作已受理并留痕），用 §3.4 的 confirmed=false + message 回吐原因，
        // 不新增 §3.3 之外的 HTTP 错误码。
        return rejection == null
                ? dispatchGateService.summary().withConfirmation(orderId)
                : dispatchGateService.summary().withRejection(rejection, orderId);
    }

    /**
     * 动作词归一：去空白、中文按钮原文按 {@link InterventionAction} 映射，
     * 其余按 {@code Locale.ROOT} 小写兜底（MEDIUM.21／MEDIUM.22）。
     */
    private static String normalizeAction(InterveneRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw badRequest("干预请求缺 action");
        }
        return InterventionAction.normalize(request.action());
    }

    /**
     * 来源校验：人工干预只允许 {@link GateSource#MANUAL}；缺省按 MANUAL 处理
     * （前端不传 {@code source}，而人工干预本就只能是人工冻结）。
     *
     * <p>显式传 {@code AUTO} 直接 400——{@code AUTO} 是气象链路的自动熔断，
     * 人工端点不得伪造，也不得越过 {@code WeatherGateTrigger} 解除（HIGH.1）。
     * 按《1112》§3.3，八个动作统一适用，不再对 {@code reset} 网开一面。
     */
    private static GateSource requireManualSource(InterveneRequest request, String action) {
        GateSource source = request.source() == null ? GateSource.MANUAL : request.source();
        if (source != GateSource.MANUAL) {
            throw badRequest("人工干预接口只接受人工冻结来源：" + action + " 收到 " + source);
        }
        return source;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
