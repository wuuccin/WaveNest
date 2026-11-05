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
 * @param previousCourseDeg 上一版<b>观测</b>对地航向，度（不是快照主轴方位，见下）
 * @param currentCourseDeg  本版观测对地航向，度
 * @param courseDeltaDeg    两版方位差（取最短弧，度，恒为非负）
 * @param triggerReason     触发原因（追溯用）
 * @param tsMs              触发时刻，Unix 毫秒
 *
 * <p><b>口径变更（10-23 F4）</b>：这两个方位原为「预测主轴方位」，切精算版后快照主轴取的是
 * 参考线走向（{@code course(s)}），不随船转弯而变，用它判「船是否改成向」会永远判不出跳变；
 * 故统一改为<b>观测对地航向</b>，两种实现下语义一致。
 */
public record RecomputeRequest(
        String mmsi,
        long snapshotVersion,
        double previousCourseDeg,
        double currentCourseDeg,
        double courseDeltaDeg,
        String triggerReason,
        long tsMs) {

    /**
     * 强制三条不变式（评审 LOW.22）。
     *
     * <p>本记录的 javadoc 早就写明「{@code courseDeltaDeg} 恒为非负的最短弧差」，
     * 但自动生成的构造器一条都不验。承接方（{@link LoggingRecomputeRequester}）要靠
     * {@code mmsi} 反查观测、要靠 {@code courseDeltaDeg} 决定日志与去重强度，
     * 拿到 null 船号或负的弧差时它既算不下去也报不出来——坏信号在源头就失败，
     * 比在承接方抛一个「NPE 或 Map 查不到」要可查得多。
     *
     * <p>不校验 {@code previousCourseDeg}/{@code currentCourseDeg} 的值域：
     * 它们是「观测对地航向」，AIS 源可能给 0～359.9 以外值（如 360 表示未知），
     * 那是上游数据源的口径问题，过度收紧会让一条脏 AIS 反而炸掉整条预测链。
     */
    public RecomputeRequest {
        if (mmsi == null || mmsi.isBlank()) {
            throw new IllegalArgumentException("mmsi 不能为空：重算信号须可定位到船");
        }
        if (!(courseDeltaDeg >= 0.0) || courseDeltaDeg > 180.0) {
            // 用 !(x >= 0) 而不是 x < 0：NaN 在后者里也会漏过（NaN < 0 为 false）
            throw new IllegalArgumentException(
                    "courseDeltaDeg 必须为 [0, 180] 的最短弧差：" + courseDeltaDeg + "（mmsi=" + mmsi + "）");
        }
    }
}
