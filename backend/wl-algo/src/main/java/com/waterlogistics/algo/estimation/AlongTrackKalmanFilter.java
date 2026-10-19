package com.waterlogistics.algo.estimation;

/**
 * 步骤 C：沿航道<b>一维卡尔曼滤波</b>（{@code 80} §3.2.5）。
 *
 * <p>状态 {@code x = [s, v_s]ᵀ}，观测 {@code z = s̃}（由 {@link WaterwayGeometry} 投影得到）；
 * 把二维船位压到沿航道一维后做常速模型滤波，横向另由 {@link LateralOuFilter} 负责（§3.2.6）。
 *
 * <h3>预测</h3>
 * <pre>
 *   F = [[1, Δt], [0, 1]]
 *   Q = q · [[Δt³/3, Δt²/2], [Δt²/2, Δt]]      // 连续白噪声加速度模型，q = 加速度功率谱密度
 *   x⁻ = F x ,  P⁻ = F P Fᵀ + Q
 * </pre>
 *
 * <h3>更新</h3>
 * <pre>
 *   H = [1, 0] ,  S = H P⁻ Hᵀ + r_s ,  K = P⁻ Hᵀ / S
 *   x  = x⁻ + K (z − H x⁻) ,  P = (I − K H) P⁻
 * </pre>
 *
 * <h3>观测噪声（关键，不能写成常数）</h3>
 * <p>AIS 位置误差 {@code σ_pos} 投影到航道方向后被放大 {@code 1/cos θ}
 * （{@code θ} 为航迹与航道夹角；越接近垂直，该观测对纵向越没信息）：
 * <pre>
 *   r_s = (σ_pos / cos θ_clamped)² ,  θ_clamped = min(θ, θ_maxDeg)
 * </pre>
 * 用 {@code θ_maxDeg} 截断避免 {@code cos θ → 0} 时除零爆炸。
 *
 * <h3>★ 内河双向航道口径</h3>
 * <p>{@code v_s} <b>带符号</b>（沿中心线正向为正、上行为负）。
 * <b>上行船的 {@code v_s} 稳定为负是正常工况，不得判为掉头或逆向</b>；
 * 限幅按 {@code |v_s|} 与 {@code vMaxCraftMs} 比较，无需区分正负。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB；参数全由调用方注入。
 */
public final class AlongTrackKalmanFilter {

    private final double processNoiseQ;
    private final double sigmaPosM;
    private final double thetaMaxDeg;
    private final double vMaxCraftMs;
    private final double initialVelocityStdMs;

    /** 状态：弧长 s（m）与纵向速度 v_s（m/s，带符号）。 */
    private double s;
    private double vAlongMs;

    /** 协方差（对称，只存 3 个独立元素）。 */
    private double pSS;
    private double pSV;
    private double pVV;

    private boolean initialized;

    /** 最近一次更新的新息与它的方差（前提监测 §3.2.8 的输入）。 */
    private double lastInnovation;
    private double lastInnovationVariance;

    /**
     * @param processNoiseQ        加速度功率谱密度 {@code q}，{@code m²/s³}
     * @param sigmaPosM            AIS 位置误差标准差，m
     * @param thetaMaxDeg          {@code θ} 截断上界，度（须在 (0, 90) 内）
     * @param vMaxCraftMs          船速限幅，m/s（作用于 {@code |v_s|}）
     * @param initialVelocityStdMs 初始化时纵向速度标准差，m/s
     */
    public AlongTrackKalmanFilter(double processNoiseQ, double sigmaPosM, double thetaMaxDeg,
            double vMaxCraftMs, double initialVelocityStdMs) {
        if (!(processNoiseQ > 0) || !(sigmaPosM > 0) || !(vMaxCraftMs > 0)) {
            throw new IllegalArgumentException("q / σ_pos / vMax 必须为正");
        }
        if (!(thetaMaxDeg > 0) || thetaMaxDeg >= 90.0) {
            throw new IllegalArgumentException("thetaMaxDeg 须在 (0, 90) 内：" + thetaMaxDeg);
        }
        if (!(initialVelocityStdMs > 0)) {
            throw new IllegalArgumentException("initialVelocityStdMs 必须为正");
        }
        this.processNoiseQ = processNoiseQ;
        this.sigmaPosM = sigmaPosM;
        this.thetaMaxDeg = thetaMaxDeg;
        this.vMaxCraftMs = vMaxCraftMs;
        this.initialVelocityStdMs = initialVelocityStdMs;
    }

    /**
     * 用首个观测初始化（{@code 80} §3.2.5「初始化」）。
     *
     * <p>{@code v_s = sog · cos(§ΔCOG)}，{@code ΔCOG = cog − 航道走向}，<b>带符号</b>：
     * 上行船得到负值属正常工况。初值协方差 {@code P = diag(σ_pos², initialVelocityStd²)}。
     *
     * @param observedS         投影得到的弧长，m
     * @param sogMs             对地航速，m/s
     * @param cogDeg            对地航向，度
     * @param waterwayCourseDeg 该处航道走向，度（{@link WaterwayGeometry#courseDegAt}）
     */
    public void initialize(double observedS, double sogMs, double cogDeg,
            double waterwayCourseDeg) {
        if (!Double.isFinite(observedS) || !Double.isFinite(sogMs)) {
            throw new IllegalArgumentException("初始观测必须为有限值");
        }
        double signedDeltaRad = signedShortestDeltaRad(waterwayCourseDeg, cogDeg);

        this.s = observedS;
        this.vAlongMs = clampVelocity(sogMs * Math.cos(signedDeltaRad));
        this.pSS = sigmaPosM * sigmaPosM;
        this.pSV = 0.0;
        this.pVV = initialVelocityStdMs * initialVelocityStdMs;
        this.lastInnovation = 0.0;
        this.lastInnovationVariance = sigmaPosM * sigmaPosM;
        this.initialized = true;
    }

    /** 是否已初始化。 */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 预测步。
     *
     * @param deltaTSec 时间增量，s（须为正）
     * @return {@code [s, v_s]}（预测后的）
     * @throws IllegalStateException 未初始化时抛出
     */
    public double[] predict(double deltaTSec) {
        requireInitialized();
        if (!(deltaTSec > 0)) {
            throw new IllegalArgumentException("Δt 必须为正：" + deltaTSec);
        }

        double dt = deltaTSec;
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;

        // x⁻ = F x
        double predictedS = s + vAlongMs * dt;
        double predictedV = vAlongMs;

        // P⁻ = F P Fᵀ + Q
        double transformedSS = pSS + 2 * dt * pSV + dt2 * pVV;
        double transformedSV = pSV + dt * pVV;
        double transformedVV = pVV;

        pSS = transformedSS + processNoiseQ * dt3 / 3.0;
        pSV = transformedSV + processNoiseQ * dt2 / 2.0;
        pVV = transformedVV + processNoiseQ * dt;

        s = predictedS;
        vAlongMs = clampVelocity(predictedV);
        return new double[] {s, vAlongMs};
    }

    /**
     * 更新步。
     *
     * @param observedS 观测弧长 {@code z}，m
     * @param thetaRad  航迹与航道夹角 {@code θ}，弧度（算观测噪声用）
     * @return {@code [s, v_s]}（更新后的）
     * @throws IllegalStateException 未初始化时抛出
     */
    public double[] update(double observedS, double thetaRad) {
        requireInitialized();
        if (!Double.isFinite(observedS)) {
            throw new IllegalArgumentException("观测弧长必须为有限值");
        }

        double rS = observationVariance(thetaRad);
        double innovation = observedS - s;                 // z − H x⁻
        double innovationVariance = pSS + rS;              // S = H P⁻ Hᵀ + r_s

        double kS = pSS / innovationVariance;
        double kV = pSV / innovationVariance;

        s += kS * innovation;
        vAlongMs = clampVelocity(vAlongMs + kV * innovation);

        // P = (I − K H) P⁻
        double newPSS = pSS - kS * pSS;
        double newPSV = pSV - kS * pSV;
        double newPVV = pVV - kV * pSV;
        pSS = newPSS;
        pSV = newPSV;
        pVV = newPVV;

        lastInnovation = innovation;
        lastInnovationVariance = innovationVariance;
        return new double[] {s, vAlongMs};
    }

    /** 最近一次更新的新息 {@code z − H x⁻}（归一化后即 NIS 的输入，§3.2.8）。 */
    public double lastInnovation() {
        return lastInnovation;
    }

    /** 最近一次更新的新息方差 {@code S}。 */
    public double lastInnovationVariance() {
        return lastInnovationVariance;
    }

    /** 归一化新息 {@code z / √S}——尺度无关，可跨船比较（前提监测用）。 */
    public double normalizedInnovation() {
        return lastInnovationVariance <= 0.0 ? 0.0 : lastInnovation / Math.sqrt(lastInnovationVariance);
    }

    /** 当前弧长估计，m。 */
    public double s() {
        return s;
    }

    /** 当前纵向速度估计，m/s（带符号；上行为负属正常）。 */
    public double vAlongMs() {
        return vAlongMs;
    }

    /** 当前弧长方差 {@code P_ss}。 */
    public double varianceS() {
        return pSS;
    }

    /** 当前纵向速度方差 {@code P_vv}。 */
    public double varianceV() {
        return pVV;
    }

    /**
     * 观测噪声方差 {@code r_s = (σ_pos / cos θ_clamped)²}。
     *
     * @param thetaRad 航迹与航道夹角，弧度
     * @return 观测噪声方差，{@code m²}
     */
    public double observationVariance(double thetaRad) {
        double thetaDeg = Math.min(Math.abs(Math.toDegrees(thetaRad)), thetaMaxDeg);
        double cosTheta = Math.cos(Math.toRadians(thetaDeg));
        double amplified = sigmaPosM / cosTheta;
        return amplified * amplified;
    }

    // ───────────── 内部工具 ─────────────

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("滤波器尚未初始化：请先调用 initialize(...)");
        }
    }

    private double clampVelocity(double velocity) {
        return Math.max(-vMaxCraftMs, Math.min(vMaxCraftMs, velocity));
    }

    /**
     * 带符号最短角差（{@code to − from}），弧度，范围 {@code (−π, π]}。
     *
     * <p>取最短弧是必须的：航向 350° → 10° 实际只差 20°，直接相减会得到 340° 的假差值。
     */
    static double signedShortestDeltaRad(double fromDeg, double toDeg) {
        double delta = ConstantVelocityPredictor.normalizeDeg(toDeg)
                - ConstantVelocityPredictor.normalizeDeg(fromDeg);
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta <= -180.0) {
            delta += 360.0;
        }
        return Math.toRadians(delta);
    }

    /** 两个航向之间的最短夹角，度。 */
    public static double shortestAngleDiffDeg(double cogA, double cogB) {
        return Math.abs(Math.toDegrees(signedShortestDeltaRad(cogA, cogB)));
    }
}
