package com.waterlogistics.algo.inflight;

/**
 * 占位时间窗（半开区间 {@code [startMs, endMs)}），
 * 口径依《29-各组算法层接口定义》§3.4.1(2)「时隙占用模型（<b>半开区间</b> ＋ 占位链）」。
 *
 * <p>本类型只表达「某机巢/迫停点在某时间段已被占用」，供功能 10 第二级做时隙重叠判定
 * （《84》功能10 第4章 4.3 时隙可行性检查 D1、4.4 时隙重叠判定 D5）。
 *
 * @param startMs 占用起始时刻，Unix 毫秒（含）
 * @param endMs   占用结束时刻，Unix 毫秒（不含）
 */
public record TimeWindow(long startMs, long endMs) {

    public TimeWindow {
        if (endMs <= startMs) {
            throw new IllegalArgumentException(
                    "占位时窗须为非空半开区间 [startMs, endMs)，实测 startMs=" + startMs + "、endMs=" + endMs);
        }
    }

    /** 时窗长度，毫秒 */
    public long durationMs() {
        return endMs - startMs;
    }

    /**
     * 与另一时窗是否重叠（半开区间口径：端点相接不算重叠）。
     *
     * @param other 另一占位时窗
     * @return 存在非空交集时为 {@code true}
     */
    public boolean overlaps(TimeWindow other) {
        return startMs < other.endMs && other.startMs < endMs;
    }
}