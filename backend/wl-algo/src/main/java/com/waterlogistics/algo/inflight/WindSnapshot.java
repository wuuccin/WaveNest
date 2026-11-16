package com.waterlogistics.algo.inflight;

/**
 * 风况快照，对应《84-E组算法方案》§7.3 输入字段「风况快照（风速风向 ＋ 预测序列起点）」。
 *
 * <p>来源 M1（经 data.weather；A 组不生产风况，见《29》§2 第 5 行）。
 *
 * <p><b>区间字段的单位</b>：{@code windSpeedMinMs}／{@code windSpeedMaxMs} 与 {@code windSpeedMs}
 * 的 {@code Ms} 都是<b>米每秒（m/s）</b>，不是毫秒。本库另有 {@code slotBufferMs}／{@code arrivalEtaMs}
 * 把 {@code Ms} 用作毫秒，二者不可混读（11-16 任务清单 1-4／2-3）。
 *
 * <p><b>区间可空、点估计仍在</b>：1-4 裁定风况给区间（A4 鲁棒档用上下界），但上游暂只给单点时
 * 仍可只填 {@code windSpeedMs}、区间两字段留空走点估计档。保留原三参构造器兼容既有调用方。
 *
 * @param windSpeedMs     点估计风速，米每秒（m/s）
 * @param windDirDeg      风向，度
 * @param sequenceStartTs 预测序列起点，Unix 毫秒
 * @param windSpeedMinMs  区间下界风速，米每秒（m/s）；可空＝上游未给区间
 * @param windSpeedMaxMs  区间上界风速，米每秒（m/s）；可空＝上游未给区间
 */
public record WindSnapshot(double windSpeedMs, double windDirDeg, long sequenceStartTs,
        Double windSpeedMinMs, Double windSpeedMaxMs) {

    /** 兼容构造器：上游只给点估计、无区间（区间两字段为 null，判档链看点估计档）。 */
    public WindSnapshot(double windSpeedMs, double windDirDeg, long sequenceStartTs) {
        this(windSpeedMs, windDirDeg, sequenceStartTs, null, null);
    }

    public WindSnapshot {
        if (!Double.isFinite(windSpeedMs) || windSpeedMs < 0) {
            throw new IllegalArgumentException("风速须为非负有限值，实测 windSpeedMs=" + windSpeedMs);
        }
        if (!Double.isFinite(windDirDeg)) {
            throw new IllegalArgumentException("风向须为有限值，实测 windDirDeg=" + windDirDeg);
        }
        if (windSpeedMinMs != null && (!Double.isFinite(windSpeedMinMs) || windSpeedMinMs < 0)) {
            throw new IllegalArgumentException("区间下界风速须为非负有限值（m/s），实测=" + windSpeedMinMs);
        }
        if (windSpeedMaxMs != null && (!Double.isFinite(windSpeedMaxMs) || windSpeedMaxMs < 0)) {
            throw new IllegalArgumentException("区间上界风速须为非负有限值（m/s），实测=" + windSpeedMaxMs);
        }
        if (windSpeedMinMs != null && windSpeedMaxMs != null && windSpeedMinMs > windSpeedMaxMs) {
            throw new IllegalArgumentException("区间下界风速不得大于上界（m/s），实测 ["
                    + windSpeedMinMs + ", " + windSpeedMaxMs + "]");
        }
    }

    /** 是否给定了完整风速区间（上下界都在）——A4 鲁棒档的入口条件。 */
    public boolean hasWindRange() {
        return windSpeedMinMs != null && windSpeedMaxMs != null;
    }
}