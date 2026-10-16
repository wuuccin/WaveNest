package com.waterlogistics.dispatch.prediction;

/**
 * 重算请求信号（M2 · 卢肄文）——预测刷新驱动链的<b>出端</b>。
 *
 * <p>语义：常驻层刷新某船预测后，若发现<b>预测前提已实质改变</b>（航向跳变超阈），
 * 说明既有会面点所依据的航迹已经不成立，须请 M3 做 6a 会面点重算
 * （《32-M3-动态会面》§3.3、《81-B组算法方案》§2.5）。
 *
 * <p><b>本记录是 M2 内部的驱动信号，不是 M3 的 {@code RendezvousRecalcRequest}</b>：
 * 后者的 {@code droneId}／{@code snapshotVersion}／{@code stateSnapshot} 等字段
 * 取决于「哪一单、哪一架、哪一轮组合」，属求解上下文；本信号只回答
 * 「<b>哪条船的预测变了、变了多少</b>」，由承接方据此补齐上下文后再调 M3 门面
 * ——这样沿用《包结构设计》§二 铁律 2「M2 调 M3 只经 {@code RendezvousFacade}」，
 * 且不把「求解上下文」的职责塞进常驻预测层。
 *
 * @param mmsi              目标船标识
 * @param snapshotVersion   触发时的快照版本号（M2 内部为 {@code long} 单调递增）
 * @param previousCourseDeg 上一版预测主轴方位，度
 * @param currentCourseDeg  本版预测主轴方位，度
 * @param courseDeltaDeg    两版方位差（取最短弧，度，恒为非负）
 * @param triggerReason     触发原因（追溯用）
 * @param tsMs              触发时刻，Unix 毫秒
 */
public record RecomputeRequest(
        String mmsi,
        long snapshotVersion,
        double previousCourseDeg,
        double currentCourseDeg,
        double courseDeltaDeg,
        String triggerReason,
        long tsMs) {
}
