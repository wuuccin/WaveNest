package com.waterlogistics.algo.inflight;

/**
 * 风况快照，对应《84-E组算法方案》§7.3 输入字段「风况快照（风速风向 ＋ 预测序列起点）」。
 *
 * <p>来源 M1（经 data.weather；A 组不生产风况，见《29》§2 第 5 行）。
 *
 * @param windSpeedMs     风速，米每秒
 * @param windDirDeg      风向，度
 * @param sequenceStartTs 预测序列起点，Unix 毫秒
 */
public record WindSnapshot(double windSpeedMs, double windDirDeg, long sequenceStartTs) {

    public WindSnapshot {
        if (!Double.isFinite(windSpeedMs) || windSpeedMs < 0) {
            throw new IllegalArgumentException("风速须为非负有限值，实测 windSpeedMs=" + windSpeedMs);
        }
        if (!Double.isFinite(windDirDeg)) {
            throw new IllegalArgumentException("风向须为有限值，实测 windDirDeg=" + windDirDeg);
        }
    }
}