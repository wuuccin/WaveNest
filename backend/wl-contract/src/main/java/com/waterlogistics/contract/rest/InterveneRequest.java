package com.waterlogistics.contract.rest;

/**
 * {@code POST /api/intervene} 的请求体 —— 人工干预派单闸门与订单（D2 · 2026-10-22）。
 *
 * <p>动作共八个归一值，见《1112-功能做实批次｜接口约定》§3.2：
 * {@code adjust_meet}／{@code withdraw}／{@code cancel}／{@code abort}／{@code manual}
 * 为订单级动作，{@code orderId} 必填；{@code trip}／{@code clear}／{@code reset}
 * 为闸门级动作，{@code orderId} 可空。
 *
 * <p>前端 {@code App.vue} 发送中文按钮原文且不传 {@code source}；由
 * {@code GateController} 归一为英文 action，{@code source} 空值按
 * {@link GateSource#MANUAL} 处理，显式传 {@link GateSource#AUTO} 一律 400。
 *
 * <p>边界：干预只操作派单闸门与订单这一层，不扩到「飞行中换机」——
 * 飞行中不换机是本项目的既定口径（回收后重派）。
 *
 * @param action  归一后的英文动作，八个取值之一（大小写敏感）
 * @param orderId 订单号；订单级动作必填，闸门级动作可空
 * @param source  熔断来源；本接口只收 {@link GateSource#MANUAL}，空值按 MANUAL 处理
 */
public record InterveneRequest(String action, String orderId, GateSource source) {
}
