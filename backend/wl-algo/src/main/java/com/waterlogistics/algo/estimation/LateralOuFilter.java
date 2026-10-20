package com.waterlogistics.algo.estimation;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 步骤 D：横向 <b>OU（Ornstein–Uhlenbeck）模型 ＋ 换道机动检测</b>（{@code 80} §3.2.6，
 * 滤波形式见 §3.2.5 末段）。
 *
 * <h3>为什么横向也要滤波</h3>
 * <p>若不滤，{@link WaterwayGeometry} 的原始投影值会直接成为预测初值 {@code e₀}，
 * 而横向速度 {@code deDt} 又是它的差分——<b>噪声会被放大成假的「换道机动」</b>。
 * 故横向用均值回复过程的一维卡尔曼：
 * <pre>
 *   转移：e⁻ = e_mean + φ·(e − e_mean) ,   φ = exp(−Δt / τ_e)
 *         P_e⁻ = φ²·P_e + (1 − φ²)·σ_e²
 *   更新：S_e = P_e⁻ + r_e ,   K_e = P_e⁻ / S_e ,   r_e = σ_pos²
 *         e = e⁻ + K_e·(ẽ − e⁻) ,   P_e = (1 − K_e)·P_e⁻
 *   横速：deDt = (e_k − e_{k−1}) / Δt ，再过 deDtWindowSec 滑动平均
 * </pre>
 *
 * <h3>★ 回复目标取 {@code e_mean} 而不是 0</h3>
 * <p>内河船常<b>靠航道一侧</b>行驶。若以中心线 {@code e = 0} 作回复目标，会给所有偏侧航行的
 * 船制造<b>系统性横向偏差</b>（XTE 恒不为零、带宽被无谓撑大）。{@code e_mean} 由近
 * {@code eMeanWindowSec} 窗内 {@code e} 的均值估计——航道只有一条中心线、无车道划分时，
 * 它即为「惯常航路」。
 *
 * <h3>换道机动检测</h3>
 * <p>连续 {@code lcCount} 个采样满足 {@code |Δe| > lcStepM} 且<b>同号</b>，判为换道机动
 * （{@code 21} §5.1 的「换道」属量变，前提仍成立）。机动期间：
 * <ul>
 *   <li>横向预测改为「当前横速线性外推到目标车道中心」：{@code e_pred(τ) = e₀ + deDt·min(τ, τ_lc)}；</li>
 *   <li>{@code σ_cross} 按 {@code lcInflate} 倍放大，覆盖外推不确定性；</li>
 *   <li>机动结束（{@code |deDt|} 回落至阈值以下）后回到 OU 预测。</li>
 * </ul>
 * <p>开关 {@code lcModelEnabled} 关闭时<b>只做 σ_cross 膨胀、不做位置偏移</b>，避免过拟合（首版建议关）。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB；参数全由调用方注入。
 */
public final class LateralOuFilter {

    /**
     * 参数。
     *
     * @param tauESec         回复时间常数 {@code τ_e}，s
     * @param sigmaEM         稳态标准差 {@code σ_e}，m
     * @param sigmaPosM       AIS 位置误差标准差，m（观测噪声 {@code r_e = σ_pos²}）
     * @param eMeanWindowSec  {@code e_mean} 估计窗，s
     * @param deDtWindowSec   横向速度滑动平均窗，s
     * @param lcStepM         换道判据的单步横向位移门限，m
     * @param lcCount         换道判据需连续满足的采样数
     * @param lcInflate       机动期间 {@code σ_cross} 放大倍数
     * @param tauLcSec        机动期线性外推的最长时间，s
     * @param lcModelEnabled  是否启用「位置偏移」；关闭时仅做 σ 膨胀
     * @param halfWidthM      航道半宽 {@code W/2}，m（约束 {@code e ∈ [−W/2, W/2]}）
     */
    public record Params(double tauESec, double sigmaEM, double sigmaPosM,
            double eMeanWindowSec, double deDtWindowSec,
            double lcStepM, int lcCount, double lcInflate, double tauLcSec,
            boolean lcModelEnabled, double halfWidthM) {

        public Params {
            if (!(tauESec > 0) || !(sigmaEM > 0) || !(sigmaPosM > 0) || !(halfWidthM > 0)) {
                throw new IllegalArgumentException("τ_e / σ_e / σ_pos / 半宽 必须为正");
            }
            if (lcCount < 1 || !(lcInflate >= 1.0)) {
                throw new IllegalArgumentException("lcCount≥1 且 lcInflate≥1");
            }
        }

        /** 演示缺省值（{@code sys_param} 尚无这些键，按纪律不臆造键名）。 */
        public static Params defaults() {
            return new Params(120.0, 15.0, 20.0, 300.0, 60.0,
                    5.0, 3, 2.0, 120.0, false, 50.0);
        }
    }

    /** 一次更新的输出。 */
    public record State(double e, double deDtMs, double varianceE, double eMean,
            boolean laneChange) {
    }

    private final Params params;

    private double e;
    private double pE;
    private boolean initialized;

    private long lastTsMs;
    private double lastRawE;

    /** 近窗 {@code e} 样本（时间戳 ＋ 值），用于 {@code e_mean}。 */
    private final Deque<double[]> history = new ArrayDeque<>();

    /** 近窗横速样本（时间戳 ＋ 瞬时 {@code deDt}），用于滑动平均。 */
    private final Deque<double[]> deDtHistory = new ArrayDeque<>();

    /** 连续同号横向位移的计数与方向。 */
    private int consecutiveStepCount;
    private int consecutiveStepSign;

    private double smoothedDeDtMs;
    private boolean laneChange;

    public LateralOuFilter(Params params) {
        if (params == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        this.params = params;
    }

    /**
     * 初始化（首帧横向投影值直接作为初值）。
     *
     * @param rawE  原始横向投影值，m
     * @param tsMs  观测时刻，Unix 毫秒
     */
    public void initialize(double rawE, long tsMs) {
        this.e = clampToChannel(rawE);
        this.pE = params.sigmaEM() * params.sigmaEM();
        this.lastTsMs = tsMs;
        this.lastRawE = rawE;
        this.smoothedDeDtMs = 0.0;
        this.laneChange = false;
        this.consecutiveStepCount = 0;
        this.consecutiveStepSign = 0;
        this.history.clear();
        this.deDtHistory.clear();
        pushHistory(tsMs, this.e);
        this.initialized = true;
    }

    /**
     * 用新观测推进一拍（内部完成 OU 预测 ＋ 量测更新 ＋ 横速平滑 ＋ 换道判定）。
     *
     * @param rawE 原始横向投影值，m
     * @param tsMs 观测时刻，Unix 毫秒
     * @return 更新后的状态
     * @throws IllegalStateException 未初始化或时间未前进时抛出
     */
    public State update(double rawE, long tsMs) {
        if (!initialized) {
            throw new IllegalStateException("滤波器尚未初始化：请先调用 initialize(...)");
        }
        long deltaTMs = tsMs - lastTsMs;
        if (deltaTMs <= 0) {
            throw new IllegalStateException("时间未前进：Δt=" + deltaTMs + " ms");
        }
        double deltaTSec = deltaTMs / 1000.0;

        // ① 预测（OU 转移，回复目标为 e_mean）
        double eMean = currentMean();
        double phi = Math.exp(-deltaTSec / params.tauESec());
        double predictedE = eMean + phi * (e - eMean);
        double predictedVariance = phi * phi * pE
                + (1.0 - phi * phi) * params.sigmaEM() * params.sigmaEM();

        // ② 更新（观测噪声 r_e = σ_pos²）
        double rE = params.sigmaPosM() * params.sigmaPosM();
        double innovationVariance = predictedVariance + rE;
        double gain = predictedVariance / innovationVariance;
        double measuredE = clampToChannel(rawE);
        e = clampToChannel(predictedE + gain * (measuredE - predictedE));
        pE = (1.0 - gain) * predictedVariance;

        // ③ 横速：单步差分后按窗滑动平均
        double instantDeDt = (e - lastRawE) / deltaTSec;
        smoothedDeDtMs = movingAverageDeDt(tsMs, instantDeDt);

        // ④ 换道检测：连续同号且超步长门限
        double step = e - lastRawE;
        updateLaneChangeCounter(step);

        lastTsMs = tsMs;
        lastRawE = e;
        pushHistory(tsMs, e);

        return new State(e, smoothedDeDtMs, pE, eMean, laneChange);
    }

    /**
     * 预测 {@code τ} 秒后的横向偏移。
     *
     * <p>机动期（且开关打开）按横速线性外推、封顶 {@code tauLcSec}；否则按 OU 回复。
     *
     * @param tauSec 预测时距，s
     * @return 横向偏移，m（已按航道半宽夹紧）
     */
    public double predictedE(double tauSec) {
        if (tauSec <= 0.0) {
            return e;
        }
        double eMean = currentMean();

        if (laneChange && params.lcModelEnabled()) {
            double cappedTau = Math.min(tauSec, params.tauLcSec());
            return clampToChannel(e + smoothedDeDtMs * cappedTau);
        }

        double phi = Math.exp(-tauSec / params.tauESec());
        return clampToChannel(eMean + phi * (e - eMean));
    }

    /**
     * 横向 {@code σ} 的放大倍数：机动期间取 {@code lcInflate}，否则 1。
     *
     * <p>注意与 {@link Params#lcModelEnabled()} 无关——<b>关闭位置偏移时仍要放大 σ</b>，
     * 否则机动期的带宽度会低估不确定性。
     */
    public double sigmaCrossMultiplier() {
        return laneChange ? params.lcInflate() : 1.0;
    }

    /** 当前横向偏移，m。 */
    public double e() {
        return e;
    }

    /** 平滑后的横向速度，m/s。 */
    public double deDtMs() {
        return smoothedDeDtMs;
    }

    /** 是否处于换道机动期。 */
    public boolean laneChange() {
        return laneChange;
    }

    /**
     * 当前横向方差 {@code pE}，{@code m²}。
     *
     * <p>步骤 E 的 {@code Var_e(τ) = φ²·pE + (1 − φ²)·σ_e²} 需要它作为初值；
     * 只读访问器，不改变滤波行为。
     */
    public double varianceE() {
        return pE;
    }

    // ───────────── 内部工具 ─────────────

    private void updateLaneChangeCounter(double stepM) {
        boolean exceeds = Math.abs(stepM) > params.lcStepM();
        int sign = stepM > 0 ? 1 : (stepM < 0 ? -1 : 0);

        if (!exceeds) {
            consecutiveStepCount = 0;
            consecutiveStepSign = 0;
            laneChange = false;
            return;
        }

        if (sign == consecutiveStepSign) {
            consecutiveStepCount++;
        } else {
            consecutiveStepSign = sign;
            consecutiveStepCount = 1;
        }

        laneChange = consecutiveStepCount >= params.lcCount();
    }

    /**
     * 横速滑动平均：把瞬时 {@code deDt} 入窗，返回窗内均值。
     *
     * <p>方案要求「{@code deDt = (e_k − e_{k−1}) / Δt}，再过 {@code deDtWindowSec} 滑动平均」
     * ——单步差分会放大投影噪声，不平滑就会在 OU 回复期误判换道。
     */
    private double movingAverageDeDt(long nowTsMs, double instantValue) {
        deDtHistory.addLast(new double[] {nowTsMs, instantValue});

        double windowSec = params.deDtWindowSec();
        while (!deDtHistory.isEmpty()
                && (nowTsMs - (long) deDtHistory.peekFirst()[0]) / 1000.0 > windowSec) {
            deDtHistory.pollFirst();
        }

        double sum = 0.0;
        for (double[] sample : deDtHistory) {
            sum += sample[1];
        }
        return deDtHistory.isEmpty() ? instantValue : sum / deDtHistory.size();
    }

    private double currentMean() {
        if (history.isEmpty()) {
            return e;
        }
        long newest = (long) history.peekLast()[0];
        double windowSec = params.eMeanWindowSec();
        double sum = 0.0;
        int count = 0;
        for (double[] sample : history) {
            if ((newest - (long) sample[0]) / 1000.0 <= windowSec) {
                sum += sample[1];
                count++;
            }
        }
        return count == 0 ? e : sum / count;
    }

    private void pushHistory(long tsMs, double value) {
        history.addLast(new double[] {tsMs, value});
        // 有界：只保留最长窗口的两倍时间范围
        long newest = tsMs;
        double keepSec = Math.max(params.eMeanWindowSec(), params.deDtWindowSec()) * 2.0;
        while (!history.isEmpty()
                && (newest - (long) history.peekFirst()[0]) / 1000.0 > keepSec) {
            history.pollFirst();
        }
    }

    private double clampToChannel(double value) {
        double half = params.halfWidthM();
        return Math.max(-half, Math.min(half, value));
    }
}
