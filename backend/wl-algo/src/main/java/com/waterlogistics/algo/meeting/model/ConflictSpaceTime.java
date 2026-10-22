package com.waterlogistics.algo.meeting.model;

/**
 * 冲突时空区间——{@code B_ConflictResult.conflictSpaceTime}（《81-B组算法方案》§2.3 第（4）项）。
 *
 * <p>{@code tBegin == tEnd} 表示单采样点的瞬时冲突包络（合法）；区间两端均含。
 * 空白 {@code zone} 统一归为 {@code null}，避免同一物理事实出现两种记录等值形态。
 *
 * @param zone   空间区域标识；检测对象①「机—机」无台账区域时为 {@code null}，
 *               ②「航迹 × 空域台账」填空域标识
 * @param tBegin 冲突区间起（含），Unix 毫秒
 * @param tEnd   冲突区间止（含），Unix 毫秒
 */
public record ConflictSpaceTime(

        String zone,

        long tBegin,

        long tEnd) {

    public ConflictSpaceTime {
        if (zone != null) {
            zone = zone.strip();
            if (zone.isEmpty()) {
                zone = null;
            }
        }
        if (tBegin < EpochMillis.MIN_VALID_MS || tEnd < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException("冲突区间时刻须为 Unix 毫秒值（下界 "
                    + EpochMillis.MIN_VALID_MS + "），实际 tBegin=" + tBegin + ", tEnd=" + tEnd);
        }
        if (tBegin > tEnd) {
            throw new IllegalArgumentException(
                    "冲突区间起不得晚于止，实际 tBegin=" + tBegin + ", tEnd=" + tEnd);
        }
    }
}
