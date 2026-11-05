package com.waterlogistics.contract.rest;

/**
 * {@code POST /api/intervene} 的请求体 —— 人工干预派单闸门（D2 · 2026-10-22）。
 *
 * <p>操作语义直接映射 {@link DispatchGateService}：
 *
 * <table>
 *   <tr><th>action</th><th>source</th><th>效果</th></tr>
 *   <tr><td>{@code trip}</td><td>{@link GateSource}（必填）</td><td>熔断／冻结闸门，记来源</td></tr>
 *   <tr><td>{@code clear}</td><td>{@link GateSource}（必填）</td><td>解除指定来源；全部解除后闸门恢复</td></tr>
 *   <tr><td>{@code reset}</td><td>忽略</td><td>一键复位（清空所有来源）</td></tr>
 * </table>
 *
 * <p>边界：干预只操作<b>派单闸门</b>这一层，不扩到「飞行中换机」——
 * 飞行中不换机是本项目的既定口径（回收后重派），干预端点不得与之矛盾。
 *
 * @param action  操作：trip / clear / reset（大小写敏感）
 * @param source 熔断来源；trip/clear 必填、reset 可空
 */
public record InterveneRequest(String action, GateSource source) {
}
