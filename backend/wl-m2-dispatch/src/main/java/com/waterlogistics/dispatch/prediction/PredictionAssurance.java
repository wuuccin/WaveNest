package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import java.util.List;
import java.util.Optional;

/**
 * 预测输出的「区间断言网」（M2 · 卢肄文 · 10-23 F4 第 1 步：<b>先补网，再切换</b>）。
 *
 * <p>任务清单原文：「切换<b>之前</b>，先给预测输出补合理区间断言——经纬度有限且非 NaN、
 * 落在赣江经纬度框内、{@code sigmaAlong}／{@code sigmaCross} 有限且为正、ETA 不早于当前时刻。
 * 没有这张网就切换，等于闭着眼睛改数值。」
 *
 * <p>本类把那张网做成可复用的纯判定：<b>不抛异常、不改数据</b>，只回答「这一版输出合不合理」，
 * 由 {@link PredictionService} 决定处置（精算版不过网 → 本帧回落匀速版并计数，绝不硬发）。
 *
 * <h3>两级判定（为什么分两级）</h3>
 * <ul>
 *   <li>{@link #checkStructure}——<b>所有模式</b>都要过：有限性、σ 为正、σ 随 τ 单调不减、
 *       时刻严格递增、有效期非负、起点不在未来。这些与地理位置无关，匀速版也必须满足。</li>
 *   <li>{@link #check}——在结构判定之外再加<b>地理框</b>：精算版的参考线来自本船实测轨迹，
 *       一旦轨迹本身是脏数据（换船、跳点、坐标系混用），产出会安静地漂到千里之外；
 *       匀速版是纯几何外推，跑在哪个坐标域由仿真决定，故只对它做结构判定。</li>
 * </ul>
 *
 * <p><b>时间口径</b>：快照没有 ETA 字段，清单里的「ETA 不早于当前时刻」落成两条可判定的等价物——
 * ① 起点时刻不得晚于「现在」超过 {@value #CLOCK_SKEW_TOLERANCE_MS} ms（时钟漂移容忍）；
 * ② 点列时刻严格递增（迟到点会被这条抓到）。
 */
public final class PredictionAssurance {

    /** 起点时刻允许超前「现在」的上限，ms：容忍机器与 AIS 源之间的时钟漂移。 */
    static final long CLOCK_SKEW_TOLERANCE_MS = 300_000L;

    /** 违约代码。日志与单测都按它断言，不改文案。 */
    public enum Code {

        /** 点列为空。 */
        EMPTY("EMPTY"),

        /** 出现 NaN／无穷。 */
        NON_FINITE("NON_FINITE"),

        /** 经纬度落在框外。 */
        OUT_OF_BOUNDS("OUT_OF_BOUNDS"),

        /** σ 非正。 */
        SIGMA_NON_POSITIVE("SIGMA_NON_POSITIVE"),

        /** σ 随外推时长下降（精算链路的状态被接错时的典型表现）。 */
        SIGMA_NOT_MONOTONE("SIGMA_NOT_MONOTONE"),

        /** 点列时刻非严格递增。 */
        TIME_NOT_MONOTONE("TIME_NOT_MONOTONE"),

        /** 有效期为负。 */
        VALIDITY_NEGATIVE("VALIDITY_NEGATIVE"),

        /** 起点时刻远在未来。 */
        T0_IN_FUTURE("T0_IN_FUTURE");

        private final String code;

        Code(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * 一次违约。
     *
     * @param code   违约代码
     * @param detail 人读细节（进日志，含出问题的下标与数值）
     */
    public record Violation(Code code, String detail) {
    }

    /**
     * 经纬度框。
     *
     * @param minLon 西界
     * @param maxLon 东界
     * @param minLat 南界
     * @param maxLat 北界
     */
    public record Bounds(double minLon, double maxLon, double minLat, double maxLat) {

        public Bounds {
            if (!Double.isFinite(minLon) || !Double.isFinite(maxLon)
                    || !Double.isFinite(minLat) || !Double.isFinite(maxLat)) {
                throw new IllegalArgumentException("框的四界必须为有限值");
            }
            if (minLon >= maxLon || minLat >= maxLat) {
                throw new IllegalArgumentException("框的四界顺序非法：" + minLon + ".." + maxLon
                        + " / " + minLat + ".." + maxLat);
            }
        }

        /** 点是否在框内（含边界）。 */
        public boolean contains(double lon, double lat) {
            return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
        }

        /**
         * 演示域框：<b>仿真域 ∪ 赣江航段</b>。
         *
         * <p>取并集而不是只取赣江，是因为仓库里的 M1 仿真器发的是
         * {@code 114.30E／30.60N}（{@code application-m1-sim.yml}），而赣江航段在
         * {@code 115.5–116.5E／28.3–29.1N}；只框赣江会让端到端联调的每一帧都被拦下、
         * 全部回落匀速版——那就成了「切了等于没切」。
         *
         * <p>框的作用是抓「坐标系混用／换错了船」这类灾难性错误（例：跑到上海 121.47E），
         * 不是精细合规检查，故取并集不收紧；正式部署按《赣江场景项目文档》收窄到航段时，
         * 用属性 {@code wavenest.m2.prediction.bbox.*} 覆盖即可，不必改代码。
         */
        public static Bounds demoArea() {
            return new Bounds(114.00, 116.50, 28.30, 30.90);
        }

        /**
         * 赣江航段演示框（南昌段，含两端余量）。
         *
         * <p>正式部署（真赣江水域数据）时用这一档：把 {@code wavenest.m2.prediction.bbox.*}
         * 配成这四个数即可。
         */
        public static Bounds ganRiver() {
            return new Bounds(115.50, 116.50, 28.30, 29.10);
        }

        /** 不设地理约束（等价于只剩结构判定）。 */
        public static Bounds unbounded() {
            return new Bounds(-180.0, 180.0, -90.0, 90.0);
        }
    }

    private final Bounds bounds;

    public PredictionAssurance(Bounds bounds) {
        if (bounds == null) {
            throw new IllegalArgumentException("框不得为 null");
        }
        this.bounds = bounds;
    }

    /** 当前生效的地理框。 */
    public Bounds bounds() {
        return bounds;
    }

    /**
     * 只做结构判定（有限性、σ 正且单调、时刻递增、有效期非负、起点不在未来）。
     *
     * @param t0Ms       快照起点时刻，Unix 毫秒
     * @param nowMs      判定时刻（「现在」），Unix 毫秒
     * @param validitySec 有效期，s
     * @param points     预测点列（含 τ=0）
     * @return 违约项；全部通过时为空
     */
    public Optional<Violation> checkStructure(long t0Ms, long nowMs, long validitySec,
            List<EstimatedPoint> points) {
        if (points == null || points.isEmpty()) {
            return Optional.of(new Violation(Code.EMPTY, "预测点列为空"));
        }
        if (validitySec < 0L) {
            return Optional.of(new Violation(Code.VALIDITY_NEGATIVE,
                    "有效期为负：" + validitySec + " s"));
        }
        if (t0Ms > nowMs + CLOCK_SKEW_TOLERANCE_MS) {
            return Optional.of(new Violation(Code.T0_IN_FUTURE,
                    "起点时刻 " + t0Ms + " 超前现在 " + nowMs + " 超过 "
                            + CLOCK_SKEW_TOLERANCE_MS + " ms"));
        }

        long previousT = Long.MIN_VALUE;
        double previousAlong = 0.0;
        double previousCross = 0.0;
        boolean first = true;

        for (int index = 0; index < points.size(); index++) {
            EstimatedPoint point = points.get(index);
            if (point == null) {
                return Optional.of(new Violation(Code.NON_FINITE, "第 " + index + " 个点为 null"));
            }
            if (!Double.isFinite(point.lon()) || !Double.isFinite(point.lat())
                    || !Double.isFinite(point.sigmaAlongM())
                    || !Double.isFinite(point.sigmaCrossM())) {
                return Optional.of(new Violation(Code.NON_FINITE, "第 " + index
                        + " 个点含 NaN／无穷：t=" + point.tMs() + " lon=" + point.lon()
                        + " lat=" + point.lat() + " σ∥=" + point.sigmaAlongM()
                        + " σ⊥=" + point.sigmaCrossM()));
            }
            if (!(point.sigmaAlongM() > 0.0) || !(point.sigmaCrossM() > 0.0)) {
                return Optional.of(new Violation(Code.SIGMA_NON_POSITIVE, "第 " + index
                        + " 个点 σ 非正：σ∥=" + point.sigmaAlongM() + " σ⊥=" + point.sigmaCrossM()));
            }
            if (first) {
                first = false;
            } else {
                if (point.tMs() <= previousT) {
                    return Optional.of(new Violation(Code.TIME_NOT_MONOTONE,
                            "第 " + index + " 个点时刻未前进：" + previousT + " → " + point.tMs()));
                }
                // 容许 1e-9 的浮点回差：同一状态重复计算时 σ 可能差最后一位
                if (point.sigmaAlongM() < previousAlong - 1e-9
                        || point.sigmaCrossM() < previousCross - 1e-9) {
                    return Optional.of(new Violation(Code.SIGMA_NOT_MONOTONE, "第 " + index
                            + " 个点 σ 下降：σ∥ " + previousAlong + " → " + point.sigmaAlongM()
                            + "，σ⊥ " + previousCross + " → " + point.sigmaCrossM()));
                }
            }
            previousT = point.tMs();
            previousAlong = point.sigmaAlongM();
            previousCross = point.sigmaCrossM();
        }
        return Optional.empty();
    }

    /**
     * 结构判定 ＋ 地理框判定。
     *
     * @param t0Ms        快照起点时刻，Unix 毫秒
     * @param nowMs       判定时刻，Unix 毫秒
     * @param validitySec 有效期，s
     * @param points      预测点列
     * @return 违约项；全部通过时为空
     */
    public Optional<Violation> check(long t0Ms, long nowMs, long validitySec,
            List<EstimatedPoint> points) {
        Optional<Violation> structural = checkStructure(t0Ms, nowMs, validitySec, points);
        if (structural.isPresent()) {
            return structural;
        }
        for (int index = 0; index < points.size(); index++) {
            EstimatedPoint point = points.get(index);
            if (!bounds.contains(point.lon(), point.lat())) {
                return Optional.of(new Violation(Code.OUT_OF_BOUNDS, "第 " + index
                        + " 个点落在框外：lon=" + String.format("%.5f", point.lon())
                        + " lat=" + String.format("%.5f", point.lat())
                        + "，框 [" + bounds.minLon() + ", " + bounds.maxLon() + "] × ["
                        + bounds.minLat() + ", " + bounds.maxLat() + "]"));
            }
        }
        return Optional.empty();
    }
}
