package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 航道中心线分段限速查询（11-03 M4 · 吴可欣）。
 *
 * <p>实现 {@link SpeedLimitLookup}，按「沿中心线的弧长 {@code s}」返回该处限速<b>绝对值</b>
 * （m/s，恒为正）。符号由 {@code MultiStepPredictor} 内部按 {@code sign(v_s)} 补上，本类不处理
 * 上下行方向——与 {@link SpeedLimitLookup} 的契约一致。
 *
 * <h3>数据来源（C7 裁定，见 {@code 77} §7）</h3>
 * <p>配置键 {@code wavenest.m2.prediction.segment-speed-limits}，{@code application.yml} 列表，
 * 每项 {@code {fromS, toS, speedMs}}：{@code s} 为沿中心线弧长（米），{@code speedMs} 为限速
 * （米/秒）。选配置键而非表，是「不新增表、不用重灌数据库」。
 *
 * <h3>配置串格式（与中心线 {@code centerline} 的分号风格一致）</h3>
 * <pre>{@code
 * "0,2000,4.0;2000,5000,3.0;5000,5780,2.5"
 * }</pre>
 * <ul>
 *   <li>段与段之间用 {@code ;} 分隔；</li>
 *   <li>每段三项用 {@code ,} 分隔：{@code fromS,toS,speedMs}；</li>
 *   <li>必须按 {@code fromS} 升序、段之间不重叠；</li>
 *   <li>落在所有段之外的 {@code s}（含 {@code s < 第一段 fromS} 与
 *       {@code s >= 末段 toS}）返回 {@link #UNBOUNDED_MS}（不限速），与
 *       {@link SpeedLimitLookup#unbounded()} 同义——航段外没有限速数据。</li>
 * </ul>
 *
 * <p>配置为空或非法时返回「恒不限速」的实例，预测链路退化为现状（等价 {@code unbounded()}），
 * 不会因一条配置写坏把整条预测链路拦停。
 *
 * <p><b>切换点</b>：{@link #switchPointsM()} 把各段端点交给预测器用于切分子步。限速切换点与
 * 中心线顶点不重合时，若不切分，一个子步会跨过切换点而整步沿用旧限速——解析积分的前提
 * （子步内限速为常量）不成立，误差可达百米量级。
 */
public final class CenterlineSpeedLimitLookup implements SpeedLimitLookup {

    /** 不限速标记：取一个不可能达到的大值，语义与 {@code unbounded()} 对齐。 */
    private static final double UNBOUNDED_MS = Double.MAX_VALUE;

    /** 恒不限速的兜底实例（配置空或非法时用）。 */
    private static final CenterlineSpeedLimitLookup UNBOUNDED =
            new CenterlineSpeedLimitLookup(List.of());

    /** 一段限速：[fromS, toS) 区间内限速 speedMs（m/s）。 */
    private record Segment(double fromS, double toS, double speedMs) {
    }

    /** 按 {@code fromS} 升序、不重叠的分段表。 */
    private final List<Segment> segments;

    /** 分段边界的弧长位置（升序去重），供预测器切分子步；构造时一次算好，避免逐子步重建。 */
    private final double[] switchPointsM;

    private CenterlineSpeedLimitLookup(List<Segment> segments) {
        this.segments = segments;
        this.switchPointsM = buildSwitchPoints(segments);
    }

    @Override
    public double speedLimitMs(double s) {
        for (Segment seg : segments) {
            if (s >= seg.fromS() && s < seg.toS()) {
                return seg.speedMs();
            }
        }
        return UNBOUNDED_MS;
    }

    /**
     * 各段 {@code fromS}／{@code toS} 的升序去重列表，m。
     *
     * <p>{@link #parse} 已保证 {@code fromS} 升序且 {@code fromS >= 上一段 toS}，故按段顺序依次追加
     * 两个端点天然非降，只需去掉相邻重复（首尾相接的两段只有一个真实切换点）。
     *
     * <p>段间留空隙时（如 {@code 0,2000,4.0;3000,5000,3.0}）空隙两端都要保留：{@code 2000} 是
     * 「退出限速段」（回到不限速）、{@code 3000} 是「进入限速段」，都是限速发生变化的位置。
     */
    @Override
    public double[] switchPointsM() {
        return switchPointsM.clone();
    }

    private static double[] buildSwitchPoints(List<Segment> segments) {
        double[] raw = new double[segments.size() * 2];
        int n = 0;
        for (Segment seg : segments) {
            if (n == 0 || raw[n - 1] != seg.fromS()) {
                raw[n++] = seg.fromS();
            }
            if (raw[n - 1] != seg.toS()) {
                raw[n++] = seg.toS();
            }
        }
        return Arrays.copyOf(raw, n);
    }

    /**
     * 从配置串解析分段限速；空串或解析失败时返回「恒不限速」的兜底实例。
     *
     * @param spec 配置串，格式 {@code "fromS,toS,speedMs;..."}，可为 {@code null} 或空
     * @return 解析出的分段限速；空或非法时等价 {@link SpeedLimitLookup#unbounded()}
     */
    public static CenterlineSpeedLimitLookup parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return UNBOUNDED;
        }
        List<Segment> list = new ArrayList<>();
        double previousTo = Double.NEGATIVE_INFINITY;
        for (String item : spec.split(";")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != 3) {
                return UNBOUNDED;
            }
            double fromS = Double.parseDouble(parts[0].trim());
            double toS = Double.parseDouble(parts[1].trim());
            double speedMs = Double.parseDouble(parts[2].trim());
            if (!(fromS < toS) || !(speedMs > 0.0)) {
                return UNBOUNDED;
            }
            if (fromS < previousTo) {
                return UNBOUNDED; // 段不按 fromS 升序或重叠，判非法，回落不限速
            }
            list.add(new Segment(fromS, toS, speedMs));
            previousTo = toS;
        }
        return list.isEmpty() ? UNBOUNDED : new CenterlineSpeedLimitLookup(list);
    }
}
