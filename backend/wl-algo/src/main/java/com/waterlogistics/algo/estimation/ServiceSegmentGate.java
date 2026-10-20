package com.waterlogistics.algo.estimation;

import java.util.List;

/**
 * 空间闸门：求船舶<b>驶入服务航段的时刻</b>（{@code 80} §3.1.3「驶入时刻」）。
 *
 * <h3>判据与算法</h3>
 * <p>服务航段由运维配置给出航道里程区间 {@code [sStart, sEnd]}（按<b>沿航道弧长</b>算，
 * 不是直线距离、也不是机巢能力范围）。本类在预测点列上求<b>首个</b>使
 * {@code s(t) ∈ [sStart, sEnd]} 的时刻，并在相邻两点间<b>线性插值</b>，一趟扫描 {@code O(N)}。
 *
 * <p><b>★ 不得假设 {@code s(t)} 单调、也不得用二分</b>（§3.1.3 原文）：内河船舶可能上行
 * （{@code v_s < 0}）、可能中途掉头。故：
 * <ul>
 *   <li>按<b>时间顺序</b>扫描，只要某一对相邻点跨越了区间边界，就算进入；</li>
 *   <li>跨越方式统一处理：{@code s_prev < sStart ≤ s_curr}（从下游侧进入）或
 *       {@code s_prev > sEnd ≥ s_curr}（从上游侧进入）；</li>
 *   <li>两点之间<b>整段跨过</b>窄航段（快船）也能捕获——此时按"进入边界"的那个点在段内插值。</li>
 * </ul>
 *
 * <p><b>本类只回答"何时驶入"，不判"是否该延后"</b>：出口语义与唤醒时刻由模块层按
 * {@code 80} §3.1.4 的原因码表处理（驶入时刻 → 定时唤醒；永不驶入 → 拒绝）。
 */
public final class ServiceSegmentGate {

    private ServiceSegmentGate() {
    }

    /**
     * 驶入结果。
     *
     * @param reaches      预测时域内是否会驶入服务航段
     * @param enterTsMs    驶入时刻，Unix 毫秒；{@code reaches=false} 时无意义（返回 0）
     * @param enterS       驶入时的弧长（即被跨越的那条边界），m；{@code reaches=false} 时为 0
     */
    public record EntryResult(boolean reaches, long enterTsMs, double enterS) {
    }

    /**
     * 求首个驶入时刻。
     *
     * @param points 预测点列（时间升序，至少 1 个点）
     * @param geometry 航道中心线几何
     * @param sStart 服务航段起点弧长，m
     * @param sEnd   服务航段终点弧长，m（须 ≥ {@code sStart}）
     * @return 驶入结果
     * @throws IllegalArgumentException 入参不满足计算前提时抛出
     */
    public static EntryResult firstEntry(List<EstimatedPoint> points, WaterwayGeometry geometry,
            double sStart, double sEnd) {

        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("预测点列不得为空——无预测则无法求解驶入时刻");
        }
        if (geometry == null) {
            throw new IllegalArgumentException("航道几何不得为空");
        }
        if (sStart > sEnd) {
            throw new IllegalArgumentException(
                    "服务航段区间非法：sStart(" + sStart + ") > sEnd(" + sEnd + ")");
        }

        int count = points.size();
        double[] s = new double[count];
        for (int i = 0; i < count; i++) {
            EstimatedPoint point = points.get(i);
            s[i] = geometry.project(point.lon(), point.lat()).s();
        }

        // 首个点就在区间内：驶入时刻即预测起点
        if (inRange(s[0], sStart, sEnd)) {
            return new EntryResult(true, points.get(0).tMs(), s[0]);
        }

        // 按时间顺序扫描相邻点，找第一次跨越边界的位置
        for (int i = 1; i < count; i++) {
            double previousS = s[i - 1];
            double currentS = s[i];

            double boundary;
            if (previousS < sStart && currentS >= sStart) {
                boundary = sStart;          // 从下游侧进入（或整段跨过）
            } else if (previousS > sEnd && currentS <= sEnd) {
                boundary = sEnd;            // 从上游侧进入（上行船 / 掉头后）
            } else {
                continue;                   // 本段没有跨越进入边界
            }

            double span = currentS - previousS;
            double ratio = span == 0.0 ? 0.0 : (boundary - previousS) / span;
            ratio = Math.max(0.0, Math.min(1.0, ratio));

            long previousT = points.get(i - 1).tMs();
            long currentT = points.get(i).tMs();
            long enterTs = previousT + Math.round(ratio * (currentT - previousT));

            return new EntryResult(true, enterTs, boundary);
        }

        return new EntryResult(false, 0L, 0.0);
    }

    /**
     * 把预测点列投影成弧长序列（供可解释性与单测核对）。
     *
     * @param points   预测点列
     * @param geometry 航道几何
     * @return 与点列等长的弧长数组，m
     */
    public static double[] projectAll(List<EstimatedPoint> points, WaterwayGeometry geometry) {
        double[] s = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            EstimatedPoint point = points.get(i);
            s[i] = geometry.project(point.lon(), point.lat()).s();
        }
        return s;
    }

    private static boolean inRange(double value, double sStart, double sEnd) {
        return value >= sStart && value <= sEnd;
    }
}
