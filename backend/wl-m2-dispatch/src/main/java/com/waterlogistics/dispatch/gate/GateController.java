package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.InterveneRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工干预派单闸门端点（D2 · 2026-10-22）。
 *
 * <p>路径复用契约常量 {@link ApiPath#INTERVENE}（{@code /api/intervene}），
 * <b>不写字面量</b>。每次操作后返回最新 {@link DispatchGate} 摘要，
 * 让调用方能一次调用拿到最终状态（无须再 GET /api/orders 确认）。
 *
 * <p>支持三种操作（对应 {@link InterveneRequest#action}）：
 * <ul>
 *   <li>{@code trip}  —— 熔断／冻结，须带 {@link GateSource}；</li>
 *   <li>{@code clear} —— 解除指定来源，须带 {@link GateSource}；</li>
 *   <li>{@code reset} —— 一键复位（演示用），忽略 source。</li>
 * </ul>
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
     * @throws IllegalArgumentException 未知 action，或 trip/clear 缺 source
     */
    @PostMapping(ApiPath.INTERVENE)
    public DispatchGate intervene(@RequestBody InterveneRequest request) {
        if (request.action() == null) {
            throw new IllegalArgumentException("干预请求缺 action");
        }
        switch (request.action().toLowerCase()) {
            case "trip" -> {
                requireSource(request, "trip");
                dispatchGateService.trip(request.source());
            }
            case "clear" -> {
                requireSource(request, "clear");
                dispatchGateService.clear(request.source());
            }
            case "reset" -> dispatchGateService.reset();
            default -> throw new IllegalArgumentException(
                    "未知干预操作：" + request.action() + "；支持 trip/clear/reset");
        }
        return dispatchGateService.summary();
    }

    private static void requireSource(InterveneRequest request, String action) {
        if (request.source() == null) {
            throw new IllegalArgumentException(
                    "操作 " + action + " 必须提供 source（GateSource）");
        }
    }
}
