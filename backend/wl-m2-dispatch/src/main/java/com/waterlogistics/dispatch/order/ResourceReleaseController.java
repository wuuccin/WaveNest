package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.rest.ApiPath;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源等待释放接口（M2 · 张睿哲 · 11-19 序号 1-2）。
 *
 * <ul>
 *   <li>{@code POST /api/orders/{orderId}/resource-release}——无请求体，
 *       把「已受理待唤醒 ＋ 等待原因＝等待资源」的订单人工放回待调度。</li>
 * </ul>
 *
 * <p><b>为什么单独一个控制器，而不是加进 {@link OrderController}</b>：后者的 owner 是
 * 卢肄文，同一文件只有一个 owner；本端点只依赖 {@link CargoReadyService#releaseResourceWait}，
 * 独立成类不触碰他人文件的映射表。
 *
 * <p><b>为什么用 409 而不是 200＋{@code released=false}</b>：本端点没有请求体，
 * 「不满足释放条件」是客户端针对当前资源状态发起的动作不合法（与
 * {@link org.springframework.web.server.ResponseStatusException} 的项目口径一致，
 * 见任务清单「非等待资源／未到期返回 409『不满足释放条件』」）。
 * 响应体仍回 {@link ResourceReleaseResponse}，带当前状态与中文原因。
 */
@RestController
public class ResourceReleaseController {

    private final CargoReadyService cargoReadyService;

    public ResourceReleaseController(CargoReadyService cargoReadyService) {
        this.cargoReadyService = cargoReadyService;
    }

    /**
     * 释放资源等待。
     *
     * @param orderId 订单标识
     * @return 200＋迁移后状态；不满足条件时 409＋当前状态与原因
     */
    @PostMapping(ApiPath.RESOURCE_RELEASE_TEMPLATE)
    public ResponseEntity<ResourceReleaseResponse> release(
            @PathVariable("orderId") String orderId) {
        ResourceReleaseResponse response = cargoReadyService.releaseResourceWait(orderId);
        if (response.released()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
