package com.waterlogistics.algo.estimation;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤 E：<b>多步预测与不确定性带</b>（{@code 80-A组算法方案} §3.2.7）。
 *
 * <p>输入是步骤 C（{@link AlongTrackKalmanFilter}，给出 {@code s, v_s, P}）与步骤 D
 * （{@link LateralOuFilter}，给出 {@code e, deDt, e_mean}）的当前状态，输出
 * {@code τ = 0, Δt, 2Δt, …, horizonSec} 的一列预测点，每点带<b>分离的</b>
 * {@code σ_along}（决定会面<b>时刻</b>）与 {@code σ_cross}（决定会面<b>位置</b>）。
 *
 * <h3>五步递推（严格对齐 §3.2.7）</h3>
 * <ol>
 *   <li><b>协方差传播</b>：{@code P⁻(τ) = F P⁻(τ−Δt) Fᵀ + Q}，<b>只用预测步、无观测</b>。
 *       常速模型下 {@code F(h₂)F(h₁) = F(h₁+h₂)}、{@code Q} 也可加和，故逐拍递推与闭式
 *       {@code P⁻_ss(τ) = P_ss + 2τP_sv + τ²P_vv + qτ³/3} <b>完全等价</b>；本类用闭式
 *       （无累积舍入），并由单测交叉验证两种算法一致。</li>
 *   <li><b>速度前瞻</b>：{@code v_lim = sign(v_s) · speedLimit(s)}，<b>必须带号</b>——上行船
 *       {@code v_s < 0} 时衰减目标也是负值，否则会被往反方向拉。{@code |v_s| > limit} 时按
 *       {@code v(t) = v_lim + (v_s − v_lim)·exp(−t/tauV)} 一阶衰减。</li>
 *   <li><b>沿程推进</b>：对第 2 步闭式做<b>解析积分</b>（不用矩形积分，避免逐步累积偏差）。
 *       跨航段时在新航段限速处重新起算（以细分步实现「分段处理」，细分长度同时受航段几何边界
 *       与 {@link SpeedLimitLookup#switchPointsM() 限速切换点}共同约束）。
 *       落到顶点边界前几纳米时<b>吸附</b>到边界再续推（见 {@link #LENGTH_EPS_M}），
 *       不把浮点残差当成「已到端点」而丢弃剩余时间。</li>
 *   <li><b>横向</b>：{@code e_pred(τ)} 与 {@code Var_e(τ)} 委托 {@link LateralForecast}，
 *       由步骤 D 的 OU 转移给出（{@code Var_e(τ) = φ²·Var_e(0) + (1−φ²)·σ_e²}，
 *       {@code φ = exp(−τ/τ_e)}——§3.2.6 的离散传播；§3.2.7 第 4 步写的
 *       {@code σ_e²(1−e^{−2τ/τ_e})} 是 {@code Var_e(0) = 0} 时的特例）。</li>
 *   <li><b>不确定度合成</b>：{@code σ_along = sqrt(P⁻_ss) + α_along·τ}、
 *       {@code σ_cross = min(sqrt(Var_e) + α_cross·τ, laneHalfWidthM)}。
 *       两项 {@code α·τ} 是<b>模型误差项</b>，不是滤波器方差；没有它带宽会过于乐观。
 *       注意是<b>代数和</b>而非平方和（§5.2）。</li>
 * </ol>
 *
 * <h3>反投影</h3>
 * <p>按 {@code s(τ)} 取中心线基准点与该段走向，再沿<b>该段法向</b>偏移 {@code e_pred(τ)}
 * （{@code e} 的符号约定与 {@link WaterwayGeometry#project} 一致：前进方向右侧为正）。
 *
 * <h3>边界与约定</h3>
 * <ul>
 *   <li>{@code τ = 0} 的点是<b>当前估计</b>：{@code σ_along = sqrt(P_ss)}、
 *       {@code σ_cross = sqrt(Var_e(0))}，不含 {@code α·τ}（{@code τ = 0} 本就为 0）。</li>
 *   <li>{@code s} 越出中心线首尾时<b>夹紧</b>在端点，剩余时段的点停在端点（船已驶出参考线，
 *       由数据有效性闸门处置），不抛异常——常驻层不能因一艘船越界而中断整轮。</li>
 *   <li>{@code v_s ≈ 0} 时不衰减、{@code s} 不推进（停车），协方差仍按 {@code τ} 增长。</li>
 * </ul>
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB、无全局状态；所有阈值由调用方注入。
 */
public final class MultiStepPredictor {

    /** {@code q} 过程噪声谱密度初值，{@code m²/s³}（附录 B：只作数值保护，不承担模型误差）。 */
    public static final double DEFAULT_PROCESS_NOISE_Q = 1.0e-6;

    /** {@code tauV} 限速衰减时间常数初值，s（附录 B）。 */
    public static final double DEFAULT_TAU_V_SEC = 60.0;

    /** 速度视为「不动」的门限，m/s；仅用于子步长划分的除零保护。 */
    private static final double VELOCITY_EPS_MS = 1.0e-6;

    /** 子步长积分的时间容差，s。 */
    private static final double TIME_EPS_SEC = 1.0e-9;

    /**
     * <b>弧长</b>比较的容差，m——必须与 {@link #TIME_EPS_SEC} 分开，不得共用一个常量。
     *
     * <p><b>为什么（11-04 内部顶点边界冻结缺陷）</b>：解析积分推进到顶点时，{@code s} 会落在
     * 边界<b>之前</b>几纳米（纯浮点残差；M4 现场验证实测 2.4e-9 m）。原先两处比较共用
     * {@code 1e-9}：这个以「米」为单位的残差被当成「到边界的距离」，除以船速后不足
     * {@code 1e-9 s}，推进循环据此判定「已顶到端点」，把那一点的剩余时间（40 s）整体丢弃；
     * 下一拍 {@code s} 仍差那几纳米，于是<b>永久冻结</b>——现象是 τ 之后各预测点经纬度完全重合。
     *
     * <p><b>取 1e-6 m（1 微米）的理由</b>：
     * <ol>
     *   <li>远大于折线累计长度的浮点残差量级（{@code s ≈ 5.8 km} 时 ulp 约 1e-12 m，
     *       实测残差 2.4e-9 m），足以把「贴住边界」与「还差一截」分开；</li>
     *   <li>物理上完全可忽略：AIS 位置噪声是米级、2σ 带宽是十米级，吸附 1 微米不改变任何判据；</li>
     *   <li>与最快船速 6 m/s 组合仍给出 {@code 1.7e-7 s} 的子步，比时间容差大六个数量级，
     *       不会反过来把「贴边界」误判成「时间步过短」。</li>
     * </ol>
     */
    private static final double LENGTH_EPS_M = 1.0e-6;

    private MultiStepPredictor() {
    }

    // ───────────── 参数 ─────────────

    /**
     * 预测参数。
     *
     * <p><b>取值归 {@code sys_param}</b>（{@code 80} 附录 B 只给仿真建议初值，不得写进代码）；
     * {@link #defaults()} 仅用于本地冒烟与单测。
     *
     * @param processNoiseQ  加速度功率谱密度 {@code q}，{@code m²/s³}
     * @param tauVSec        速度向航段限速一阶衰减的时间常数，s
     * @param alphaAlongMs   纵向模型误差率 {@code α_along}，m/s（{@code σ_along} 的 {@code α·τ} 项）
     * @param alphaCrossMs   横向模型误差率 {@code α_cross}，m/s
     * @param laneHalfWidthM 航道半宽，m（{@code σ_cross} 的物理截断上界）
     * @param lTolM          纵向容忍上限，m（有效期判定用）
     * @param wTolM          横向容忍上限，m（有效期判定用）
     * @param stepSec        相邻预测点间隔，s
     * @param horizonSec     预测时域，s
     */
    public record Params(
            double processNoiseQ,
            double tauVSec,
            double alphaAlongMs,
            double alphaCrossMs,
            double laneHalfWidthM,
            double lTolM,
            double wTolM,
            long stepSec,
            long horizonSec) {

        public Params {
            if (!(processNoiseQ > 0.0)) {
                throw new IllegalArgumentException("q 必须为正（只作数值保护也须 > 0）：" + processNoiseQ);
            }
            if (!(tauVSec > 0.0)) {
                throw new IllegalArgumentException("tauV 必须为正：" + tauVSec);
            }
            if (alphaAlongMs < 0.0 || alphaCrossMs < 0.0) {
                throw new IllegalArgumentException("α 不得为负：α_along=" + alphaAlongMs
                        + ", α_cross=" + alphaCrossMs);
            }
            if (!(laneHalfWidthM > 0.0) || !(lTolM > 0.0) || !(wTolM > 0.0)) {
                throw new IllegalArgumentException("半宽与容忍上限必须为正：" + laneHalfWidthM
                        + "/" + lTolM + "/" + wTolM);
            }
            if (stepSec <= 0 || horizonSec <= 0 || stepSec > horizonSec) {
                throw new IllegalArgumentException("stepSec/horizonSec 非法：" + stepSec + "/" + horizonSec);
            }
        }

        /**
         * 附录 B 的仿真建议初值：{@code q = 1e-6}、{@code tauV = 60}、
         * {@code α_along = 0.15}、{@code α_cross = 0.02}、半宽 {@code 50}、
         * {@code lTol = 180}、{@code wTol = 40}、步长 {@code 60}、时域 {@code 1800}。
         *
         * <p>这组值自洽：据此复算的有效期约 17 min（{@code 80} 附录 B 末注）。
         */
        public static Params defaults() {
            return new Params(DEFAULT_PROCESS_NOISE_Q, DEFAULT_TAU_V_SEC,
                    0.15, 0.02, 50.0, 180.0, 40.0, 60L, 1800L);
        }

        /**
         * 由模块层已经读好的 {@link PredictionParams} 组装，其余取自附录 B 初值。
         *
         * <p>模块层把 {@code sys_param} 读出的 {@code alphaAlongMs}/{@code alphaCrossMs}/
         * {@code horizonSec}/{@code stepSec} 注入 {@link PredictionParams}，本方法负责与
         * 半宽、容忍上限、{@code q}、{@code tauV} 合并，避免算法层重复认键名。
         */
        public static Params fromPredictionParams(PredictionParams params, double laneHalfWidthM,
                double lTolM, double wTolM) {
            return new Params(DEFAULT_PROCESS_NOISE_Q, DEFAULT_TAU_V_SEC,
                    params.alphaAlongMs(), params.alphaCrossMs(),
                    laneHalfWidthM, lTolM, wTolM, params.stepSec(), params.horizonSec());
        }

        /** 预测点个数：{@code horizonSec / stepSec + 1}（含 {@code τ = 0}）。 */
        public int pointCount() {
            return (int) (horizonSec / stepSec) + 1;
        }
    }

    // ───────────── 输入侧抽象 ─────────────

    /**
     * 航段限速查询（按弧长 {@code s} 取该处限速的<b>绝对值</b>，单位 m/s，恒为正）。
     *
     * <p>符号由 {@code sign(v_s)} 在 {@link MultiStepPredictor} 内部补上——限速本身不区分上下行，
     * 但衰减目标必须带上行船的方向，否则会把上行船往反方向拉。
     */
    @FunctionalInterface
    public interface SpeedLimitLookup {

        /** 弧长 {@code s} 处的航段限速绝对值，m/s。 */
        double speedLimitMs(double s);

        /**
         * 限速切换点的弧长位置，m（升序，只含 {@code 0 < s < 中心线总长} 的内部点）。
         *
         * <p><b>为什么需要</b>：{@link MultiStepPredictor} 的子步必须被「限速发生变化的位置」
         * 切断。解析积分（{@code 80} §3.2.7 第 3 步）要求子步内 {@code v_lim} 是常量；只按几何
         * 顶点切分时，若切换点与顶点不重合，一个子步会跨过切换点、整步沿用跨越前的限速，
         * 违背「跨入新航段则按新航段限速从跨越点重新起算」。
         *
         * <p>默认空数组：常数限速（{@link #constant}）与不限速（{@link #unbounded}）没有切换点，
         * 行为与加本方法之前完全一致。实现方可以返回内部缓存数组，<b>调用方不得修改返回值</b>。
         */
        default double[] switchPointsM() {
            return new double[0];
        }

        /** 不限速（限速取一个不可能达到的大值），用于无航道限速数据的演示。 */
        static SpeedLimitLookup unbounded() {
            return s -> Double.MAX_VALUE;
        }

        /** 常数限速，m/s。 */
        static SpeedLimitLookup constant(double limitMs) {
            if (!(limitMs > 0.0)) {
                throw new IllegalArgumentException("限速必须为正：" + limitMs);
            }
            return s -> limitMs;
        }
    }

    /**
     * 横向预测的抽象（步骤 D 的出口）。
     *
     * <p>把「位置怎么走」与「方差怎么长」分开：{@link #predictedE} 取步骤 D 的 OU 回复
     * 或换道线性外推（{@code 80} §3.2.6），{@link #varianceE} 取 OU 转移的方差传播，
     * {@link #sigmaMultiplier} 取换道机动期的带宽膨胀倍数。
     */
    public interface LateralForecast {

        /** {@code τ} 秒后的横向偏移 {@code e_pred(τ)}，m（已按航道半宽夹紧）。 */
        double predictedE(double tauSec);

        /** {@code τ} 秒后的横向方差 {@code Var_e(τ)}，{@code m²}（不含 {@code α_cross·τ}）。 */
        double varianceE(double tauSec);

        /** {@code σ_cross} 的放大倍数：换道机动期取 {@code lcInflate}，否则 1。 */
        double sigmaMultiplier();

        /**
         * 纯 OU 情形（无换道机动模型）：{@code e(τ) = e_mean + (e_0 − e_mean)e^{−τ/τ_e}}，
         * {@code Var_e(τ) = φ²·Var_e(0) + (1 − φ²)·σ_e²}。
         *
         * @param e0         当前横向偏移，m
         * @param eMean      惯常横向位置 {@code e_mean}，m（<b>不是 0</b>，内河船常靠一侧行驶）
         * @param varianceE0 当前横向方差（步骤 D 的 {@code pE}），{@code m²}
         * @param tauESec    回复时间常数 {@code τ_e}，s
         * @param sigmaEM    稳态标准差 {@code σ_e}，m
         */
        static LateralForecast ou(double e0, double eMean, double varianceE0,
                double tauESec, double sigmaEM) {
            if (!(tauESec > 0.0) || !(sigmaEM > 0.0)) {
                throw new IllegalArgumentException("τ_e / σ_e 必须为正：" + tauESec + "/" + sigmaEM);
            }
            if (varianceE0 < 0.0) {
                throw new IllegalArgumentException("横向方差不得为负：" + varianceE0);
            }
            double stationary = sigmaEM * sigmaEM;
            return new LateralForecast() {
                @Override
                public double predictedE(double tauSec) {
                    if (tauSec <= 0.0) {
                        return e0;
                    }
                    double phi = Math.exp(-tauSec / tauESec);
                    return eMean + phi * (e0 - eMean);
                }

                @Override
                public double varianceE(double tauSec) {
                    if (tauSec <= 0.0) {
                        return varianceE0;
                    }
                    double phi = Math.exp(-tauSec / tauESec);
                    return phi * phi * varianceE0 + (1.0 - phi * phi) * stationary;
                }

                @Override
                public double sigmaMultiplier() {
                    return 1.0;
                }
            };
        }

        /**
         * 由步骤 D 的滤波器状态适配：位置与机动标志取自 {@link LateralOuFilter}，
         * 方差按 OU 转移传播。
         *
         * @param filter      已初始化的横向滤波器
         * @param varianceE0  当前横向方差 {@code pE}，{@code m²}（{@link LateralOuFilter#varianceE()}）
         * @param sigmaEM     稳态标准差 {@code σ_e}，m
         * @param tauESec     回复时间常数 {@code τ_e}，s
         */
        static LateralForecast from(LateralOuFilter filter, double varianceE0,
                double sigmaEM, double tauESec) {
            if (!(tauESec > 0.0) || !(sigmaEM > 0.0)) {
                throw new IllegalArgumentException("τ_e / σ_e 必须为正：" + tauESec + "/" + sigmaEM);
            }
            double stationary = sigmaEM * sigmaEM;
            return new LateralForecast() {
                @Override
                public double predictedE(double tauSec) {
                    return filter.predictedE(tauSec);
                }

                @Override
                public double varianceE(double tauSec) {
                    if (tauSec <= 0.0) {
                        return varianceE0;
                    }
                    double phi = Math.exp(-tauSec / tauESec);
                    return phi * phi * varianceE0 + (1.0 - phi * phi) * stationary;
                }

                @Override
                public double sigmaMultiplier() {
                    return filter.sigmaCrossMultiplier();
                }
            };
        }
    }

    /**
     * 预测输入：步骤 C 的状态与协方差 ＋ 步骤 D 的横向预测 ＋ 航道几何与限速。
     *
     * @param t0Ms       预测起始时刻，Unix 毫秒
     * @param s0         当前弧长估计，m
     * @param v0         当前纵向速度估计，m/s（<b>带符号</b>，上行为负属正常工况）
     * @param pSS        当前弧长方差 {@code P_ss}
     * @param pSV        当前 {@code s}–{@code v} 协方差 {@code P_sv}
     * @param pVV        当前纵向速度方差 {@code P_vv}
     * @param lateral    横向预测（步骤 D 出口）
     * @param geometry   航道中心线
     * @param speedLimit 航段限速查询
     */
    public record Input(
            long t0Ms,
            double s0,
            double v0,
            double pSS,
            double pSV,
            double pVV,
            LateralForecast lateral,
            WaterwayGeometry geometry,
            SpeedLimitLookup speedLimit) {

        public Input {
            if (!Double.isFinite(s0) || !Double.isFinite(v0)) {
                throw new IllegalArgumentException("s0 / v0 必须为有限值");
            }
            if (!(pSS >= 0.0) || !(pVV >= 0.0) || !Double.isFinite(pSV)) {
                throw new IllegalArgumentException("协方差非法：" + pSS + "/" + pSV + "/" + pVV);
            }
            if (lateral == null || geometry == null || speedLimit == null) {
                throw new IllegalArgumentException("lateral / geometry / speedLimit 不得为 null");
            }
        }
    }

    /**
     * 一个预测点。
     *
     * @param tMs          预测时刻，Unix 毫秒
     * @param tauSec       相对 {@code t0} 的预测时距 {@code τ}，s
     * @param s            沿航道弧长，m
     * @param e            横向偏移，m（前进方向右侧为正）
     * @param lon          预测经度
     * @param lat          预测纬度
     * @param sigmaAlongM  纵向 1σ，m（<b>标准差，不是方差</b>）
     * @param sigmaCrossM  横向 1σ，m
     * @param courseDeg    该处航道走向，度（置信椭圆主轴方位，见 §2.6）
     */
    public record Sample(
            long tMs,
            double tauSec,
            double s,
            double e,
            double lon,
            double lat,
            double sigmaAlongM,
            double sigmaCrossM,
            double courseDeg) {

        /** 投影成算法层对外输出对象（模块层再映射成 {@code ShipTrajPoint}）。 */
        public EstimatedPoint toEstimatedPoint() {
            return new EstimatedPoint(tMs, lon, lat, sigmaAlongM, sigmaCrossM);
        }
    }

    // ───────────── 主流程 ─────────────

    /**
     * 生成整条预测点列（含 {@code τ = 0} 的当前点）。
     *
     * @param input  预测输入
     * @param params 预测参数
     * @return 按 {@code τ} 递增的点列，长度 {@link Params#pointCount()}
     */
    public static List<Sample> predict(Input input, Params params) {
        double stepSec = params.stepSec();
        int pointCount = params.pointCount();
        double total = input.geometry().totalLengthM();

        List<Sample> samples = new ArrayList<>(pointCount);

        // 子步只允许落在「几何顶点」或「限速切换点」上：解析积分要求子步内 v_lim 是常量。
        // 两组边界在循环外取一次——segmentBoundariesM() 内部是 clone()，逐子步取会反复分配。
        double[] segmentBoundaries = input.geometry().segmentBoundariesM();
        double[] switchPoints = input.speedLimit().switchPointsM();

        // τ = 0：当前估计（不含 α·τ）
        double sigmaAlong0 = Math.sqrt(Math.max(0.0, input.pSS()));
        double sigmaCross0 = Math.min(Math.sqrt(Math.max(0.0, input.lateral().varianceE(0.0)))
                * input.lateral().sigmaMultiplier(), params.laneHalfWidthM());
        samples.add(sampleAt(input, 0.0, input.s0(), input.lateral().predictedE(0.0),
                sigmaAlong0, sigmaCross0));

        // 沿程推进的积分状态
        double s = input.s0();
        double v = input.v0();
        double tau = 0.0;

        for (int index = 1; index < pointCount; index++) {
            double targetTau = index * stepSec;
            while (tau < targetTau - TIME_EPS_SEC) {
                double remaining = targetTau - tau;
                if (Math.abs(v) < VELOCITY_EPS_MS) {
                    // 船停住：s 不推进，剩余时间不再消耗（σ 仍按 τ 增长）
                    tau = targetTau;
                    break;
                }

                // 沿行进方向的下一个边界（几何顶点或限速切换点，严格大于/小于 s，不含当前所在位置）
                double boundary = nextBoundaryM(segmentBoundaries, switchPoints, s, v, total);
                double gapM = Math.abs(boundary - s);
                if (gapM <= LENGTH_EPS_M) {
                    // 解析积分落到边界前几纳米（浮点残差）：吸附到边界的精确值，不丢弃剩余时间。
                    s = clampToWaterway(boundary, total);
                    if (s <= 0.0 || s >= total) {
                        // 真到中心线端点：剩余时间不再推进（越界由数据有效性闸门处置）
                        tau = targetTau;
                        break;
                    }
                    // 内部顶点：吸附不耗时间，下一轮 from 新航段取限速、重新起算
                    continue;
                }

                // 子步：不超过剩余时长，且不越过航段边界（分段限速的处理口径）
                double stepLength = Math.min(remaining, gapM / Math.abs(v));
                double[] advanced = advanceOneSubStep(s, v, stepLength, params, input.speedLimit());
                s = clampToWaterway(advanced[0], total);
                v = advanced[1];
                tau += stepLength;
            }

            double sigmaAlong = Math.sqrt(covarianceSS(input, params, targetTau))
                    + params.alphaAlongMs() * targetTau;
            double sigmaCross = Math.min(
                    Math.sqrt(Math.max(0.0, input.lateral().varianceE(targetTau)))
                            * input.lateral().sigmaMultiplier()
                            + params.alphaCrossMs() * targetTau,
                    params.laneHalfWidthM());

            samples.add(sampleAt(input, targetTau, s, input.lateral().predictedE(targetTau),
                    sigmaAlong, sigmaCross));
        }
        return samples;
    }

    /**
     * 有效期 {@code validitySec}（{@code 80} §3.2.8）。
     *
     * <pre>
     *   validitySec = min{ τ ∈ 步列 : σ_along(τ) &gt; lTolM 或 σ_cross(τ) &gt; wTolM }
     *   若不存在这样的 τ，则 validitySec = horizonSec
     * </pre>
     *
     * <p><b>有效期与预测时域不是一回事</b>：点列仍输出满 {@code horizonSec}，有效期只标到
     * 「还能当作可信输入」的那一段；尾部是低置信外推，由常驻层滚动刷新兜住。
     *
     * @param samples    预测点列
     * @param lTolM      纵向容忍上限，m
     * @param wTolM      横向容忍上限，m
     * @param horizonSec 预测时域，s（都不超限时返回它）
     * @return 有效时长，s
     */
    public static long validitySec(List<Sample> samples, double lTolM, double wTolM, long horizonSec) {
        for (Sample sample : samples) {
            if (sample.sigmaAlongM() > lTolM || sample.sigmaCrossM() > wTolM) {
                return (long) Math.floor(sample.tauSec());
            }
        }
        return horizonSec;
    }

    /** {@link #validitySec(List, double, double, long)} 的便捷重载（取参数里的容忍上限）。 */
    public static long validitySec(List<Sample> samples, Params params) {
        return validitySec(samples, params.lTolM(), params.wTolM(), params.horizonSec());
    }

    // ───────────── 协方差传播 ─────────────

    /**
     * 沿航道位置方差 {@code P⁻_ss(τ)}（<b>闭式</b>，等价于逐拍 {@code F P Fᵀ + Q}）。
     *
     * <pre>
     *   P⁻_ss(τ) = P_ss + 2τ·P_sv + τ²·P_vv + q·τ³/3
     * </pre>
     */
    public static double covarianceSS(Input input, Params params, double tauSec) {
        double tau2 = tauSec * tauSec;
        double value = input.pSS()
                + 2.0 * tauSec * input.pSV()
                + tau2 * input.pVV()
                + params.processNoiseQ() * tau2 * tauSec / 3.0;
        return Math.max(0.0, value);
    }

    // ───────────── 内部：积分与反投影 ─────────────

    /**
     * 推进一个子步，返回 {@code [s, v]}。
     *
     * <p>衰减段用解析积分（{@code 80} §3.2.7 第 3 步），未衰减段直接匀速；
     * {@code v_lim} 带 {@code sign(v_s)}。
     */
    private static double[] advanceOneSubStep(double s, double v, double deltaTSec,
            Params params, SpeedLimitLookup speedLimit) {
        double sign = v >= 0.0 ? 1.0 : -1.0;
        double limit = Math.abs(speedLimit.speedLimitMs(s));
        if (!(limit > 0.0) || !Double.isFinite(limit)) {
            limit = Double.MAX_VALUE;
        }
        double signedLimit = sign * limit;

        if (Math.abs(v) > limit) {
            // 一阶衰减到航段限速：v(t) = v_lim + (v_s − v_lim)·exp(−t/tauV)
            double decay = Math.exp(-deltaTSec / params.tauVSec());
            double deltaS = signedLimit * deltaTSec
                    + (v - signedLimit) * params.tauVSec() * (1.0 - decay);
            double newV = signedLimit + (v - signedLimit) * decay;
            return new double[] {s + deltaS, newV};
        }
        return new double[] {s + v * deltaTSec, v};
    }

    /**
     * 沿当前行进方向<b>下一个切分点</b>的弧长值，m（上行取更大者、下行取更小者）；
     * 没有内部切分点时取中心线端点（上行 {@code total}、下行 {@code 0}）。
     *
     * <p>切分点有两类，取其中离当前位置最近的一个：
     * <ol>
     *   <li><b>几何顶点</b>（{@code segmentBoundaries}）——跨顶点后中心线走向改变，反投影基准也变；</li>
     *   <li><b>限速切换点</b>（{@link SpeedLimitLookup#switchPointsM()}）——跨切换点后
     *       {@code v_lim} 改变，解析积分必须从跨越点重新起算（{@code 80} §3.2.7 第 3 步）。</li>
     * </ol>
     * 只按顶点切分时，切换点与顶点不重合的子步会整步沿用旧限速，误差可达百米量级。
     *
     * <p>用于把「跨入新航段则按新航段限速重新起算」落实到子步划分上：子步不被允许跨过任一切分点，
     * 因此每个子步内的限速是常量，解析积分成立。
     *
     * <p><b>为什么这里用严格比较而不是 {@code + LENGTH_EPS_M}</b>：容差写在推进循环里，
     * 语义是「落到边界前不到 1 微米就吸附过去」。若在这里把 1 微米内的边界直接跳过，
     * 子步就会跨过该切分点，整步沿用旧的限速——那正是分段处理要避免的错误。
     */
    private static double nextBoundaryM(double[] segmentBoundaries, double[] switchPoints,
            double s, double v, double total) {
        double best = v > 0.0 ? total : 0.0;
        for (double boundary : segmentBoundaries) {
            best = nearerAhead(best, boundary, s, v, total);
        }
        for (double switchPoint : switchPoints) {
            best = nearerAhead(best, switchPoint, s, v, total);
        }
        return best;
    }

    /**
     * 候选切分点在行进方向上、且比当前更近时采纳；端点与越界值由 {@code total}／{@code 0} 兜底，
     * 不参与比较（{@code switchPointsM()} 契约只保证内部点，几何端点另由基准值覆盖）。
     */
    private static double nearerAhead(double current, double candidate, double s, double v,
            double total) {
        if (!(candidate > 0.0) || !(candidate < total)) {
            return current;
        }
        if (v > 0.0) {
            return (candidate > s && candidate < current) ? candidate : current;
        }
        return (candidate < s && candidate > current) ? candidate : current;
    }

    /**
     * 组装一个预测点：先求中心线基准点与走向，再沿该段法向偏移 {@code e}。
     *
     * <p>{@code e > 0} 表示前进方向<b>右侧</b>：法向方位角为 {@code course + 90°}；
     * 平面近似下 {@code 东 = d·sin(方位)}、{@code 北 = d·cos(方位)}。
     */
    private static Sample sampleAt(Input input, double tauSec, double s, double e,
            double sigmaAlongM, double sigmaCrossM) {
        WaterwayGeometry geometry = input.geometry();
        WaterwayGeometry.Vertex center = geometry.pointAt(s);
        double courseDeg = geometry.courseDegAt(s);

        double normalRad = Math.toRadians(courseDeg + 90.0);
        double east = e * Math.sin(normalRad);
        double north = e * Math.cos(normalRad);

        double metersPerDegLon = WaterwayGeometry.METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(center.lat()));
        double lon = center.lon() + (Math.abs(metersPerDegLon) < 1.0e-9 ? 0.0 : east / metersPerDegLon);
        double lat = center.lat() + north / WaterwayGeometry.METERS_PER_DEG_LAT;

        long tMs = input.t0Ms() + Math.round(tauSec * 1000.0);
        return new Sample(tMs, tauSec, s, e, lon, lat, sigmaAlongM, sigmaCrossM, courseDeg);
    }

    private static double clampToWaterway(double s, double total) {
        return Math.max(0.0, Math.min(total, s));
    }
}
