package com.waterlogistics.algo.estimation;

/**
 * 标定因子 {@code c} 的 <b>NEES 一致性检验</b>（{@code 80-A组算法方案} §5.1、§2.6 第（3）项）。
 *
 * <h3>为什么需要 {@code c}</h3>
 * <p>本组给出的带<b>不是滤波器的预报协方差，而是含模型误差项的保守包络</b>
 * （§3.2.7 第 5 步的 {@code α·τ}）。后果是 {@code NIS₂} 会系统性偏小：若下游直接拿
 * {@code χ²(2)} 的分位数当门限，<b>实际虚警率将低于名义值、检测会变迟钝</b>。
 * 处置之一是先用 NEES 标出 {@code c}，下游判 {@code NIS₂ / c}（跨组裁决 A-1）。
 *
 * <pre>
 *   ν = (Δs, Δe) ,  Cov = R(ψ)·diag(σ_along², σ_cross²)·R(ψ)ᵀ
 *   NEES = νᵀ · Cov⁻¹ · ν            // 2 维，理论上 E[NEES] = 2
 *   m = (1/N)·Σ NEES ,  c = m / 2
 * </pre>
 *
 * <h3>样本量与置信区间</h3>
 * <p>{@code N·m ~ χ²(2N)}，故 {@code m} 的标准差为 {@code 2/√N}；{@code c = m/2} 的
 * 95% 区间宽度为 {@code 1.96/√N}。验收要求 {@code N ≥ 200} 且宽度 ≤ {@code 0.15}
 * （{@code N = 200} 时约 0.139）。
 *
 * <h3>初值</h3>
 * <p>{@code c₀ = 1.0}（中性）。理由：{@code α·τ} 项已按实测误差增长标定，带宽本身已对准
 * 覆盖率目标，{@code c} 只承担剩余系统性偏差，从 1.0 起收敛比凭经验给 0.7 之类更稳。
 *
 * <p><b>分层</b>：{@code c} 按 {@code (预测时域段 × 船速段)} 分层各标一份，层内样本不足
 * 时回退到全局 {@code c}——调用方按需持有多个实例（全局一个、每层各一个），本类只管单桶累积。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB；{@code c} 由调用方随快照下发，本类不写回任何配置。
 */
public final class NeesCalibrator {

    /** {@code c} 的初值（中性）。 */
    public static final double INITIAL_CALIBRATION_FACTOR = 1.0;

    /** NEES 的理论期望值（2 维）。 */
    public static final double THEORETICAL_NEES = 2.0;

    /** 验收最小样本量（§5.1）。 */
    public static final int MIN_SAMPLES_FOR_ACCEPTANCE = 200;

    /** {@code c} 的 95% 区间宽度上限（§5.1）。 */
    public static final double MAX_CONFIDENCE_WIDTH = 0.15;

    /**
     * 标定结果。
     *
     * @param sampleCount       样本数 {@code N}
     * @param meanNees          均值 {@code m}（理论 2.0）
     * @param calibrationFactor 标定因子 {@code c = m/2}；无样本时为 {@value #INITIAL_CALIBRATION_FACTOR}
     * @param confidenceWidth   {@code c} 的 95% 区间宽度 {@code 1.96/√N}
     * @param acceptable        是否满足 §5.1 的验收（{@code N ≥ 200} 且宽度 ≤ 0.15）
     */
    public record Result(int sampleCount, double meanNees, double calibrationFactor,
            double confidenceWidth, boolean acceptable) {
    }

    private double sumNees;
    private int sampleCount;

    /**
     * 计入一次新息。
     *
     * @param deltaS     纵向新息 {@code Δs}，m
     * @param deltaE     横向新息 {@code Δe}，m
     * @param sigmaAlong {@code σ_along}，m（须为正）
     * @param sigmaCross {@code σ_cross}，m（须为正）
     */
    public void add(double deltaS, double deltaE, double sigmaAlong, double sigmaCross) {
        addNees(nees(deltaS, deltaE, sigmaAlong, sigmaCross));
    }

    /**
     * 直接计入一个已算好的 NEES 值。
     *
     * @param neesValue NEES，须为非负有限值
     */
    public void addNees(double neesValue) {
        if (!Double.isFinite(neesValue) || neesValue < 0.0) {
            throw new IllegalArgumentException("NEES 必须为非负有限值：" + neesValue);
        }
        sumNees += neesValue;
        sampleCount++;
    }

    /**
     * 二维 NEES：{@code (Δs/σ_along)² + (Δe/σ_cross)²}。
     *
     * <p>因为主轴就是沿航道／法向，{@code Cov} 在曲线坐标下已是对角阵，
     * 不必真的构造并求逆 {@code R(ψ)·diag·R(ψ)ᵀ}——旋到主轴上再算等价，且无舍入风险。
     *
     * @param deltaS     纵向新息，m
     * @param deltaE     横向新息，m
     * @param sigmaAlong {@code σ_along}，m
     * @param sigmaCross {@code σ_cross}，m
     * @return NEES
     */
    public static double nees(double deltaS, double deltaE, double sigmaAlong, double sigmaCross) {
        checkSigma(sigmaAlong, "σ_along");
        checkSigma(sigmaCross, "σ_cross");
        double zAlong = deltaS / sigmaAlong;
        double zCross = deltaE / sigmaCross;
        return zAlong * zAlong + zCross * zCross;
    }

    /**
     * 当前的标定结果。
     *
     * @return 结果；无样本时 {@code calibrationFactor} 回落到 {@value #INITIAL_CALIBRATION_FACTOR}
     */
    public Result result() {
        if (sampleCount == 0) {
            return new Result(0, THEORETICAL_NEES, INITIAL_CALIBRATION_FACTOR,
                    Double.POSITIVE_INFINITY, false);
        }
        double mean = sumNees / sampleCount;
        double width = 1.96 / Math.sqrt(sampleCount);
        boolean acceptable = sampleCount >= MIN_SAMPLES_FOR_ACCEPTANCE && width <= MAX_CONFIDENCE_WIDTH;
        return new Result(sampleCount, mean, mean / THEORETICAL_NEES, width, acceptable);
    }

    /**
     * 当前标定因子 {@code c}；无样本时返回 {@value #INITIAL_CALIBRATION_FACTOR}。
     *
     * <p>这就是随快照 {@code calibrationFactor} 下发的值。
     */
    public double calibrationFactor() {
        return result().calibrationFactor();
    }

    /** 样本数。 */
    public int sampleCount() {
        return sampleCount;
    }

    /** 清空（复标前用）。 */
    public void reset() {
        sumNees = 0.0;
        sampleCount = 0;
    }

    private static void checkSigma(double sigma, String name) {
        if (!(sigma > 0.0) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException(name + " 必须为正有限值：" + sigma);
        }
    }
}
