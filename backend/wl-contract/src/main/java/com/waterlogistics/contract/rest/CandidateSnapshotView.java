package com.waterlogistics.contract.rest;

/**
 * 候选快照视图——按订单回查「这次派单选了谁、剩下为什么没选」的九项留痕
 * （《11-19 任务清单》§3 张睿哲·二，裁定 7）。
 *
 * <p>九项字段与建表脚本 {@code deploy/sql/020_candidate_snapshot.sql} 一一对应，
 * 字段名与前端（3-2 彭紫情）约定后不得自行改名。
 *
 * <p>会面时刻只在中选行有值：落选候选未进入会面求解，{@code meetTimeMs} 为 {@code null}，
 * 不用转场 ETA 冒充会面时刻。
 *
 * @param orderId         订单号
 * @param model           机型
 * @param nestId          机巢（组合出发巢）
 * @param droneId         机号
 * @param distKm          机巢到取货点距离（km，粗估直线）；组合缺失时为 {@code null}
 * @param meetTimeMs      定案会面时刻（Unix 毫秒，含错峰时移）；落选候选为 {@code null}
 * @param rejectReason    落选原因（判定端中文原文，多条以「；」连接）；中选者写「中选」
 * @param rankKey         择优键值 {@code "delay=…;dist=…"}，与 FeasibleCandidatePicker 两级键同式
 * @param snapshotVersion 快照版本（一致性锚）
 */
public record CandidateSnapshotView(
        String orderId,
        String model,
        String nestId,
        String droneId,
        Double distKm,
        Long meetTimeMs,
        String rejectReason,
        String rankKey,
        String snapshotVersion) {
}
