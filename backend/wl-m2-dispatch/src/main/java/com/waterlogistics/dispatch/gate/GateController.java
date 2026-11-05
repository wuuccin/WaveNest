package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.InterveneRequest;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 人工干预派单闸门端点（D2 · 2026-10-22）。
 *
 * <p>路径复用契约常量 {@link ApiPath#INTERVENE}（{@code /api/intervene}），
 * <b>不写字面量</b>。每次操作后返回最新 {@link DispatchGate} 摘要，
 * 让调用方能一次调用拿到最终状态（无须再 GET /api/orders 确认）。
 *
 * <p>支持三种操作（{@link InterveneRequest#action}，英文与前端中文标签等价）：
 * <ul>
 *   <li>{@code trip}／{@code 冻结派单} —— 熔断／冻结，来源默认且仅允许 {@link GateSource#MANUAL}；</li>
 *   <li>{@code clear}／{@code 解除冻结} —— 解除人工冻结，来源默认且仅允许 {@code MANUAL}；</li>
 *   <li>{@code reset}／{@code 重置} —— 解除全部<b>人工冻结</b>，<b>保留</b> {@code AUTO} 自动熔断
 *       （HIGH.12：气象仍超阈值时不得放开闸门），忽略 source。</li>
 * </ul>
 *
 * <h3>错误口径（HIGH.13）</h3>
 * 客户端错误（缺 action、未知 action、非 MANUAL 来源）一律抛
 * {@link ResponseStatusException}（HTTP 400）。历史实现抛 {@code IllegalArgumentException}，
 * 而全仓没有 {@code @RestControllerAdvice}，会退化成 500 + 通用错误体。
 *
 * <h3>安全边界（HIGH.1）</h3>
 * 契约《22》§7.1 要求 {@code /api/intervene} 走调度员角色 JWT；本仓库尚未引入
 * Spring Security，<b>JWT 鉴权待接入</b>。当前以最小防护替代：接口只能操作
 * {@code MANUAL} 来源（不能伪造/清除 {@code AUTO} 气象熔断），且 {@code reset} 保留
 * {@code AUTO}——匿名调用最多冻结／解除人工冻结，无法在恶劣气象下放开派单。
 *
 * <p>边界：干预只操作<b>派单闸门</b>这一层，不扩到「飞行中换机」——
 * 飞行中不换机是本项目的既定口径（回收后重派），本端点不得与之矛盾。
 */
@RestController
public class GateController {

    private final DispatchGateService dispatchGateService;

    public GateController(DispatchGateService dispatchGateService) {
        this.dispatchGateService = dispatchGateService;
    }

    /**
     * 人工干预闸门。
     *
     * @throws ResponseStatusException 400：缺 action／未知 action／来源非 MANUAL
     */
    @PostMapping(ApiPath.INTERVENE)
    public DispatchGate intervene(@RequestBody InterveneRequest request) {
        // MEDIUM.21：先 trim 再按 Locale.ROOT 归一，避免 " trip" 或土耳其语环境下
        // "TRIP"→"tırıp" 落到 default；MEDIUM.22：中文标签按语义映射，对齐前端调用
        String action = normalizeAction(request);
        switch (action) {
            case "trip" -> dispatchGateService.trip(requireManualSource(request, "trip"));
            case "clear" -> dispatchGateService.clear(requireManualSource(request, "clear"));
            case "reset" -> dispatchGateService.reset();
            default -> throw badRequest(
                    "未知干预操作：" + request.action() + "；支持 trip/clear/reset");
        }
        return dispatchGateService.summary();
    }

    /**
     * 动作词归一：去空白 + {@code Locale.ROOT} 小写；前端中文标签映射为等价动作。
     *
     * <p>前端 {@code App.vue} 发送的是中文标签（如「冻结派单」）且不带 {@code source}，
     * 历史实现只认英文动作，真实 UI 干预全部落在 default 分支失败（MEDIUM.22）。
     * 本方法把可映射的中文标签归一；无法映射的标签仍走 400 并给出支持列表。
     */
    private static String normalizeAction(InterveneRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw badRequest("干预请求缺 action");
        }
        String raw = request.action().trim();
        return switch (raw) {
            case "冻结派单", "冻结" -> "trip";
            case "解除冻结", "解冻" -> "clear";
            case "重置", "复位" -> "reset";
            default -> raw.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 来源校验：人工干预只允许 {@link GateSource#MANUAL}；缺省按 MANUAL 处理
     * （前端不传 {@code source}，而人工干预本就只能是人工冻结）。
     *
     * <p>显式传 {@code AUTO} 直接 400——{@code AUTO} 是气象链路的自动熔断，
     * 人工端点不得伪造，也不得越过 {@code WeatherGateTrigger} 解除（HIGH.1）。
     */
    private static GateSource requireManualSource(InterveneRequest request, String action) {
        GateSource source = request.source() == null ? GateSource.MANUAL : request.source();
        if (source != GateSource.MANUAL) {
            throw badRequest("人工干预仅允许操作 MANUAL 来源：" + action + " 收到 " + source
                    + "（AUTO 熔断由气象链路维护）");
        }
        return source;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
