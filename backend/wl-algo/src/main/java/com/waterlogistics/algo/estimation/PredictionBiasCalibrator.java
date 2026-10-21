package com.waterlogistics.algo.estimation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤 H：<b>预测误差回流校准</b>（{@code 80-A组算法方案} §3.2.10）。
 *
 * <p>常驻层每轮刷新时，取最近 {@code calDepth} 个历史快照在<b>当前时刻</b>的预测值
 * （在 {@code points} 上线性插值），与当前估计值比较，得到纵向／横向误差，按
 * {@code (预测时域段 × 船速段)} 分层入环形缓冲；样本数达 {@code calMinSamples} 才出建议。
 *
 * <h3>与标定因子 {@code c} 的分工（不能混）</h3>
 * <table border="1">
 *   <caption>两类校准互不替代</caption>
 *   <tr><th></th><th>校什么</th><th>周期</th><th>产出</th></tr>
 *   <tr><td><b>本类</b></td><td>预测<b>模型参数</b>（速度偏置、{@code α_along}／{@code α_cross}）</td>
 *       <td>长</td><td>建议，经 M2 决策侧确认后写 {@code sys_param}</td></tr>
 *   <tr><td>{@link NeesCalibrator}</td><td>带宽与判据的<b>匹配度</b>（{@code c}）</td>
 *       <td>短</td><td>随快照 {@code calibrationFactor} 下发</td></tr>
 * </table>
 *
 * <h3>符号校验（写错方向会让预测越校越偏，所以单独强调）</h3>
 * <p>{@code errAlong = s_est − s_pred}，故 {@code μ_along > 0} 表示<b>实际比预测靠前</b>
 * （预测低估了里程），应<b>上调</b>预测速度，偏置取 {@code +μ_along / τ̄}。
 * 以 {@code s(τ) ≈ s_0 + v·τ} 代入可验：速度增量 {@code δv} 产生里程增量约 {@code δv·τ}，
 * 令 {@code δv·τ̄ = μ_along} 即得该式。符号与量纲同时成立。
 *
 * <h3>船速分段必须带符号</h3>
 * <p>{@code v_s} 为负（上行）时按 {@code |v_s|} 分档；否则全部上行样本会挤进最低档，分层失效。
 *
 * <h3>落法：只出建议、不写回</h3>
 * <p>本类只产出 {@link Suggestion} 列表，<b>不自动写 {@code sys_param}</b>——避免单次偏差
 * 引起阈值抖动（{@code NF-8}、{@code 37} §5.14）。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB、无自动写回。
 */
public final class PredictionBiasCalibrator {

    /** 建议类别（与 §3.2.10 统计表四行一一对应）。 */
    public enum Kind {
        /** 沿航道速度加偏置 */
        ALONG_SPEED_BIAS("ALONG_SPEED_BIAS"),
        /** 放大 {@code α_along}（纵向带宽过窄） */
        ALPHA_ALONG_WIDEN("ALPHA_ALONG_WIDEN"),
        /** 放大 {@code α_cross}（横向带宽过窄） */
        ALPHA_CROSS_WIDEN("ALPHA_CROSS_WIDEN"),
        /** 缩小 {@code α_cross}（横向带宽过保守，浪费可行集） */
        ALPHA_CROSS_SHRINK("ALPHA_CROSS_SHRINK");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        /** 建议类别码。 */
        public String code() {
            return code;
        }
    }

    /**
     * 校准参数（取值归 {@code sys_param}；{@link #defaults()} 为附录 B 仿真初值）。
     *
     * @param calTimeBucketSec   预测时域分桶宽度，s
     * @param timeBucketCount    时域桶数
     * @param speedBucketEdgesMs 船速分档边界（按 {@code |v_s|}），m/s；须升序
     * @param calWindowN         层内环形缓冲容量
     * @param calMinSamples      层内最少样本数，低于此值不出建议
     * @param calPersistM        条件须连续满足的周期数（防单次异常触发调整）
     * @param kBias              偏置判定系数（{@code |μ| > kBias·σ}）
     * @param kBand              带宽过窄系数（{@code q > kBand} 即放宽）
     * @param kTight             带宽过保守系数（{@code q < kTight} 即收紧）
     * @param maxAdjustRatio     单次最大调整比例（防过冲）
     * @param maxVBiasMs         速度偏置上限，m/s
     * @param currentAlphaAlongMs 当前 {@code α_along}，m/s（建议值以它为基准）
     * @param currentAlphaCrossMs 当前 {@code α_cross}，m/s
     */
    public record Params(double calTimeBucketSec, int timeBucketCount, double[] speedBucketEdgesMs,
            int calWindowN, int calMinSamples, int calPersistM, double kBias, double kBand,
            double kTight, double maxAdjustRatio, double maxVBiasMs,
            double currentAlphaAlongMs, double currentAlphaCrossMs) {

        public Params {
            if (!(calTimeBucketSec > 0.0) || timeBucketCount < 1 || calWindowN < 1
                    || calMinSamples < 1 || calPersistM < 1) {
                throw new IllegalArgumentException("分桶／窗口／样本量参数必须为正");
            }
            if (speedBucketEdgesMs == null || speedBucketEdgesMs.length == 0) {
                throw new IllegalArgumentException("船速分档边界不得为空");
            }
            for (int i = 0; i < speedBucketEdgesMs.length; i++) {
                if (!(speedBucketEdgesMs[i] > 0.0)) {
                    throw new IllegalArgumentException("船速分档边界必须为正：" + speedBucketEdgesMs[i]);
                }
                if (i > 0 && speedBucketEdgesMs[i] <= speedBucketEdgesMs[i - 1]) {
                    throw new IllegalArgumentException("船速分档边界须严格升序");
                }
            }
            if (!(kBias > 0.0) || !(kBand > 0.0) || !(kTight > 0.0) || kBand <= kTight) {
                throw new IllegalArgumentException("系数非法：kBias=" + kBias
                        + ", kBand=" + kBand + ", kTight=" + kTight + "（须 kBand > kTight）");
            }
            if (maxAdjustRatio <= 0.0 || maxAdjustRatio >= 1.0) {
                throw new IllegalArgumentException("maxAdjustRatio 须在 (0,1) 内：" + maxAdjustRatio);
            }
            if (!(maxVBiasMs > 0.0) || !(currentAlphaAlongMs >= 0.0) || !(currentAlphaCrossMs >= 0.0)) {
                throw new IllegalArgumentException("偏置上限须为正、α 不得为负");
            }
            speedBucketEdgesMs = speedBucketEdgesMs.clone();
        }

        /** 附录 B 仿真初值：60 s 分桶 × 30 段、船速档 {@code 1.5/3.5 m/s}、窗 200、
         *  层内 30 样本、连续 5 周期、{@code kBias=1.0}、{@code kBand=1.5}、{@code kTight=0.7}、
         *  单次 ≤ 10%、偏置 ≤ 0.5 m/s，α 基准取附录 B 的 0.15／0.02。 */
        public static Params defaults() {
            return new Params(60.0, 30, new double[] {1.5, 3.5}, 200, 30, 5,
                    1.0, 1.5, 0.7, 0.10, 0.5, 0.15, 0.02);
        }

        /** 船速分档边界副本。 */
        @Override
        public double[] speedBucketEdgesMs() {
            return speedBucketEdgesMs.clone();
        }
    }

    /**
     * 一条校准样本（一次「历史预测 vs 当前估计」的比对）。
     *
     * @param tauSec    该历史快照距今的预测时距 {@code τ}，s
     * @param vAlongMs  当前纵向速度估计，m/s（<b>带符号</b>，分档时取绝对值）
     * @param errAlongM {@code s_est − s_pred(now)}，m（<b>带符号</b>：正=实际更靠前）
     * @param errCrossM {@code e_est − e_pred(now)}，m（<b>带符号</b>）
     * @param sigmaAlongM 该快照在同一 {@code τ} 处的 {@code σ_along}，m
     * @param sigmaCrossM 该快照在同一 {@code τ} 处的 {@code σ_cross}，m
     */
    public record Sample(double tauSec, double vAlongMs, double errAlongM, double errCrossM,
            double sigmaAlongM, double sigmaCrossM) {

        public Sample {
            if (!(tauSec > 0.0)) {
                throw new IllegalArgumentException("τ 必须为正：" + tauSec);
            }
            if (!Double.isFinite(errAlongM) || !Double.isFinite(errCrossM)
                    || !Double.isFinite(vAlongMs)) {
                throw new IllegalArgumentException("误差与速度必须为有限值");
            }
            if (!(sigmaAlongM > 0.0) || !(sigmaCrossM > 0.0)) {
                throw new IllegalArgumentException("σ 必须为正：" + sigmaAlongM + "/" + sigmaCrossM);
            }
        }
    }

    /**
     * 一条校准建议。
     *
     * @param kind             建议类别
     * @param timeBucket       预测时域桶序号（0 起）
     * @param speedBucket      船速桶序号（0 起）
     * @param suggestedValue   建议值（速度偏置 m/s，或调整后的 {@code α} m/s）
     * @param currentValue     当前值
     * @param observed         触发的统计量
     * @param threshold        与之比较的门限
     * @param sampleCount      层内样本数
     * @param representativeTauSec 层内代表时距 {@code τ̄}（样本 τ 的均值），s
     * @param note             人读说明
     */
    public record Suggestion(Kind kind, int timeBucket, int speedBucket, double suggestedValue,
            double currentValue, double observed, double threshold, int sampleCount,
            double representativeTauSec, String note) {
    }

    private final Params params;

    /** {@code (时域桶, 船速桶) → 环形缓冲}；用位拼接做键，避免嵌套 Map。 */
    private final Map<Long, Deque<Sample>> buckets = new LinkedHashMap<>();

    /** {@code (类别, 时域桶, 船速桶) → 连续满足的周期数}。 */
    private final Map<String, Integer> persistence = new LinkedHashMap<>();

    /**
     * @param params 校准参数
     */
    public PredictionBiasCalibrator(Params params) {
        if (params == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        this.params = params;
    }

    /**
     * 计入一条样本。
     *
     * @param sample 校准样本
     */
    public void addSample(Sample sample) {
        int timeBucket = timeBucketOf(sample.tauSec());
        int speedBucket = speedBucketOf(sample.vAlongMs());
        long key = bucketKey(timeBucket, speedBucket);
        Deque<Sample> buffer = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        buffer.addLast(sample);
        while (buffer.size() > params.calWindowN()) {
            buffer.pollFirst();
        }
    }

    /**
     * 跑一个校准周期，产出建议列表。
     *
     * <p>每调用一次即「一个周期」：{@code calPersistM} 的连续计数在本次调用中推进，
     * 建议只在连续满足达 {@code calPersistM} 个周期时才出现。
     *
     * @return 建议列表（可能为空）
     */
    public List<Suggestion> suggest() {
        List<Suggestion> suggestions = new ArrayList<>();
        for (Map.Entry<Long, Deque<Sample>> entry : buckets.entrySet()) {
            int timeBucket = (int) (entry.getKey() / 1000L);
            int speedBucket = (int) (entry.getKey() % 1000L);
            List<Sample> samples = new ArrayList<>(entry.getValue());
            if (samples.size() < params.calMinSamples()) {
                continue;
            }
            double tauBar = meanTau(samples);
            double sigmaAlongBar = meanSigmaAlong(samples);

            double muAlong = meanErrAlong(samples);
            // 注意：先判统计条件，条件成立才推进连续计数；条件不成立才复位。
            // 若把 countPersistence 直接放进 if 条件里再落到 else 复位，会「刚加一就清零」，
            // 连续门槛永远达不到（这个写法踩过一次，别再踩）。
            if (Math.abs(muAlong) > params.kBias() * sigmaAlongBar) {
                if (countPersistence(Kind.ALONG_SPEED_BIAS, timeBucket, speedBucket)) {
                    double rawBias = muAlong / tauBar;
                    double clamped = Math.max(-params.maxVBiasMs(),
                            Math.min(params.maxVBiasMs(), rawBias));
                    suggestions.add(new Suggestion(Kind.ALONG_SPEED_BIAS, timeBucket, speedBucket,
                            clamped, 0.0, muAlong, params.kBias() * sigmaAlongBar, samples.size(), tauBar,
                            String.format("μ_along=%.1f m 超过 %.1f m：实际%s预测，速度偏置 %+.3f m/s",
                                    muAlong, params.kBias() * sigmaAlongBar,
                                    muAlong > 0 ? "比预测靠前" : "比预测靠后", clamped)));
                }
            } else {
                resetPersistence(Kind.ALONG_SPEED_BIAS, timeBucket, speedBucket);
            }

            double qAlong = rmsNormalizedAlong(samples);
            if (qAlong > params.kBand()) {
                if (countPersistence(Kind.ALPHA_ALONG_WIDEN, timeBucket, speedBucket)) {
                    double ratio = Math.min(params.maxAdjustRatio(), qAlong / params.kBand() - 1.0);
                    suggestions.add(new Suggestion(Kind.ALPHA_ALONG_WIDEN, timeBucket, speedBucket,
                            params.currentAlphaAlongMs() * (1.0 + ratio), params.currentAlphaAlongMs(),
                            qAlong, params.kBand(), samples.size(), tauBar,
                            String.format("纵向归一化误差 %.2fσ 超过 %.1fσ：α_along 上调 %.1f%%",
                                    qAlong, params.kBand(), ratio * 100.0)));
                }
            } else {
                resetPersistence(Kind.ALPHA_ALONG_WIDEN, timeBucket, speedBucket);
            }

            double qCross = rmsNormalizedCross(samples);
            if (qCross > params.kBand()) {
                if (countPersistence(Kind.ALPHA_CROSS_WIDEN, timeBucket, speedBucket)) {
                    double ratio = Math.min(params.maxAdjustRatio(), qCross / params.kBand() - 1.0);
                    suggestions.add(new Suggestion(Kind.ALPHA_CROSS_WIDEN, timeBucket, speedBucket,
                            params.currentAlphaCrossMs() * (1.0 + ratio), params.currentAlphaCrossMs(),
                            qCross, params.kBand(), samples.size(), tauBar,
                            String.format("横向归一化误差 %.2fσ 超过 %.1fσ：α_cross 上调 %.1f%%",
                                    qCross, params.kBand(), ratio * 100.0)));
                }
            } else {
                resetPersistence(Kind.ALPHA_CROSS_WIDEN, timeBucket, speedBucket);
            }

            if (qCross < params.kTight()) {
                if (countPersistence(Kind.ALPHA_CROSS_SHRINK, timeBucket, speedBucket)) {
                    double ratio = Math.min(params.maxAdjustRatio(), 1.0 - qCross / params.kTight());
                    suggestions.add(new Suggestion(Kind.ALPHA_CROSS_SHRINK, timeBucket, speedBucket,
                            params.currentAlphaCrossMs() * (1.0 - ratio), params.currentAlphaCrossMs(),
                            qCross, params.kTight(), samples.size(), tauBar,
                            String.format("横向归一化误差 %.2fσ 低于 %.1fσ：带宽过于保守，α_cross 下调 %.1f%%",
                                    qCross, params.kTight(), ratio * 100.0)));
                }
            } else {
                resetPersistence(Kind.ALPHA_CROSS_SHRINK, timeBucket, speedBucket);
            }
        }
        return suggestions;
    }

    /** 已积累样本的层数（诊断用）。 */
    public int bucketCount() {
        return buckets.size();
    }

    /** 某一层的样本数（诊断用）。 */
    public int sampleCount(int timeBucket, int speedBucket) {
        Deque<Sample> buffer = buckets.get(bucketKey(timeBucket, speedBucket));
        return buffer == null ? 0 : buffer.size();
    }

    // ───────────── 统计量 ─────────────

    /**
     * 归一化 RMS 误差 {@code sqrt(mean((err/σ)²))}——<b>标定良好时约为 1</b>。
     *
     * <p>之所以用它而不是 {@code p95(|err|)} 与 {@code kBand}／{@code kTight} 比较：
     * 对良好标定的带，{@code p95(|err|) ≈ 1.96σ} 恒大于任何小于 2 的系数，判据会永远触发；
     * 而归一化 RMS 在标定良好时正好落在 1.0，{@code kBand = 1.5}／{@code kTight = 0.7}
     * 才是分布在 1.0 两侧的一对门限，与 §4.2 的验收区间 {@code [0.6, 1.2]} 同一量纲。
     */
    public static double rmsNormalizedAlong(List<Sample> samples) {
        return rmsNormalized(samples, true);
    }

    /** 横向归一化 RMS 误差，口径见 {@link #rmsNormalizedAlong(List)}。 */
    public static double rmsNormalizedCross(List<Sample> samples) {
        return rmsNormalized(samples, false);
    }

    /**
     * 误差的 95 分位（绝对值，m）——供展示与人工核对用，<b>不参与判据</b>。
     *
     * @param samples  样本
     * @param along    {@code true} 取纵向、{@code false} 取横向
     * @return 95 分位绝对值，m
     */
    public static double p95AbsError(List<Sample> samples, boolean along) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = Math.abs(along ? samples.get(i).errAlongM() : samples.get(i).errCrossM());
        }
        java.util.Arrays.sort(values);
        int index = (int) Math.ceil(0.95 * values.length) - 1;
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }

    // ───────────── 内部 ─────────────

    private static double rmsNormalized(List<Sample> samples, boolean along) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Sample sample : samples) {
            double value = along ? sample.errAlongM() / sample.sigmaAlongM()
                    : sample.errCrossM() / sample.sigmaCrossM();
            sum += value * value;
        }
        return Math.sqrt(sum / samples.size());
    }

    private static double meanTau(List<Sample> samples) {
        double sum = 0.0;
        for (Sample sample : samples) {
            sum += sample.tauSec();
        }
        return sum / samples.size();
    }

    private static double meanSigmaAlong(List<Sample> samples) {
        double sum = 0.0;
        for (Sample sample : samples) {
            sum += sample.sigmaAlongM();
        }
        return sum / samples.size();
    }

    private static double meanErrAlong(List<Sample> samples) {
        double sum = 0.0;
        for (Sample sample : samples) {
            sum += sample.errAlongM();
        }
        return sum / samples.size();
    }

    private int timeBucketOf(double tauSec) {
        int index = (int) Math.floor(tauSec / params.calTimeBucketSec());
        return Math.max(0, Math.min(params.timeBucketCount() - 1, index));
    }

    private int speedBucketOf(double vAlongMs) {
        double absV = Math.abs(vAlongMs);
        double[] edges = params.speedBucketEdgesMs();
        for (int i = 0; i < edges.length; i++) {
            if (absV < edges[i]) {
                return i;
            }
        }
        return edges.length;
    }

    private static long bucketKey(int timeBucket, int speedBucket) {
        return (long) timeBucket * 1000L + speedBucket;
    }

    private static String persistenceKey(Kind kind, int timeBucket, int speedBucket) {
        return kind.code() + "@" + timeBucket + ":" + speedBucket;
    }

    /**
     * 推进一个周期的连续计数，返回「是否已达到 {@code calPersistM}」。
     */
    private boolean countPersistence(Kind kind, int timeBucket, int speedBucket) {
        String key = persistenceKey(kind, timeBucket, speedBucket);
        int count = persistence.getOrDefault(key, 0) + 1;
        persistence.put(key, count);
        return count >= params.calPersistM();
    }

    private void resetPersistence(Kind kind, int timeBucket, int speedBucket) {
        persistence.remove(persistenceKey(kind, timeBucket, speedBucket));
    }
}
