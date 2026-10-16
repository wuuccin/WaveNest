package com.waterlogistics.algo.meeting.model;

/**
 * 冲突时空区间——{@code B_ConflictResult.conflictSpaceTime}（《81-B组算法方案》§2.3 第（4）项）。
 *
 * @param zone   空间区域标识；检测对象①「机—机」无台账区域时为 {@code null}，
 *               ②「航迹 × 空域台账」填空域标识
 * @param tBegin 冲突区间起，Unix 毫秒
 * @param tEnd   冲突区间止，Unix 毫秒
 */
public record ConflictSpaceTime(

        String zone,

        long tBegin,

        long tEnd) {
}
