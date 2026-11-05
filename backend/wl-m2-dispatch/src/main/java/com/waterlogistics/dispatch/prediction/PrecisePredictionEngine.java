package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.AisObservationFilter;
import com.waterlogistics.algo.estimation.AlongTrackKalmanFilter;
import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.LateralOuFilter;
import com.waterlogistics.algo.estimation.MultiStepPredictor;
import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.NavPhaseTracker;
import com.waterlogistics.algo.estimation.PremiseMonitor;
import com.waterlogistics.algo.estimation.PremiseStatus;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 精算预测引擎——<b>每船一份状态</b>的封装（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>把算法层 {@code estimation} 子包里各自纯函数的四步串成一条<b>有状态</b>链路：
 *
 * <pre>
 *   观测 → ① 清洗（AisObservationFilter，仅用于建参考线）
 *        → ② 曲线坐标投影（WaterwayGeometry.project → s, e）
 *        → ③ 沿航道一维卡尔曼（AlongTrackKalmanFilter：predict ＋ update）
 *        → ④ 横向 OU（LateralOuFilter：update ＋ 供 LateralForecast）
 *        → ⑤ 多步预测（MultiStepPredictor.predict → 点列 ＋ σ∇τ）
 * </pre>
 *
 * <h3>为什么这一层同时是残差出口（11-03 D1）</h3>
 * <p>「预测 ↔ 实测」的配对残差只有本类算得出来：它同时握有<b>上一版预测点列</b>
 * （{@code MultiStepPredictor.Sample} 里带曲线坐标 {@code s}/{@code e}、两条 σ 与切向）
 * 与<b>新观测在同一条参考线上的投影</b>。换到别处算，就得把点列反投影回曲线坐标、
 * 还得假设参考线没变——而参考线恰恰是本类的内部状态。故本类实现
 * {@link ResidualSampleSource}：算完即按船缓存，消费方（校准链路）按需取。
 *
 * <p><b>为什么缓存而不是每拍回调</b>：校准有节流（默认 60 s/船），每拍回调会把
 * 残差产生频率与消费频率强耦合；缓存一份「最近一窗」后，消费方什么时候来取都能取到，
 * 也才能满足接口要求的「幂等可重入」——重复索取只是重复读同一个不可变列表。
 *
 * <h3>为什么要这一层（清单第 3 条「适配有状态性」）</h3>
 * <p>算法层的类都是「纯计算」，但 {@code AlongTrackKalmanFilter}／{@code LateralOuFilter} 各自
 * 持有状态，只能<b>逐拍推进</b>；若每次预测都新建一份，接错不会报错，只会让 σ 每帧从头长起
 * ——正是清单警示的「逐帧跳变」。故本类把「哪条船、哪一版状态」管起来，服务层只按船号取用。
 *
 * <h3>参考线怎么来（退化 1，零新增依赖）</h3>
 * <p>项目没有航道中心线台账（{@code 37} 的 14 张表里没有中心线／拓扑），故按
 * {@code 80} §3.2.4「退化路径」第 1 级：<b>参考线取本船最近 {@code refColdStartSec} 的实测轨迹</b>。
 * 与文档的差别两处，均在此写明原因：
 * <ul>
 *   <li><b>折线尾端沿末段走向直线延伸</b>（长 {@code horizonSec · vMaxCraftMs ＋ 500 m}）：
 *       实测窗只有几分钟、几百米，而预测时域是 1800 s；不延伸则 {@code s} 很快顶到折线端点，
 *       {@link MultiStepPredictor} 会把点列夹在端点停住（预测「冻住」）。延伸段只是把「船沿当前
 *       直线继续走」这条退化假设显式化，不引入任何新数据。</li>
 *   <li><b>参考线建立后不再逐轮换成「上一版预测轨迹」</b>：{@code AlongTrackKalmanFilter} 没有
 *       重锚 API，换线必须重新 {@code initialize} 把协方差清零——那会让 σ 每帧回缩、
 *       白白丢掉「观测累积使不确定度下降」这一核心收益，正是清单第 3 条警示的失败模式。
 *       台账落地后改读真中心线时，才需要连同重锚一起补。</li>
 * </ul>
 *
 * <h3>接不上的前置依赖（清单第 4 条要求写明）</h3>
 * <ul>
 *   <li><b>偏差校准 {@code PredictionBiasCalibrator}／{@code NeesCalibrator} 未接</b>：
 *       两者的输入是「预测—实测配对样本」及其落表（{@code A-11} 的预测误差样本表），
 *       该表在 M6 侧尚未建；本类改为把每拍的归一化新息累积进快照的 {@code zSeries}
 *       （窗长 32），先让 E 组与标定侧有真数据可吃，等表建好再接。</li>
 *   <li><b>前提监测</b>：{@link PremiseMonitor} 与 {@link NavPhaseTracker} 已按船接入，
 *       每帧观测推进一次，P 判据的参数取算法层缺省值；结论经 {@code Outcome.premise}
 *       透出到 {@code ship_traj.premise}。</li>
 *   <li><b>航段限速</b>：中心线已接入，分段限速由 {@code wavenest.m2.prediction.segment-speed-limits}
 *       解析成 {@link SpeedLimitLookup} 后注入（11-03 M4）；未配置时恒不限速，
 *       车速另受滤波器自己的 {@code vMaxCraftMs} 限幅。</li>
 *   <li><b>速度偏置的读取点（77 §8 G1）</b>：{@code predAlongSpeedBiasMs} 由校准器写回
 *       {@code sys_param}，本类通过 {@link #alongSpeedForPrediction} 在<b>每次预测时</b>取值并
 *       加到参与推进的纵向速度上。此前该键「有定义、有种子、无消费点」，写回链路通而校准闭环断在
 *       预测侧；接入后偏置缺省为 0，行为与接入前完全一致。</li>
 * </ul>
 *
 * <p><b>本类不感知 Spring、不写库</b>：装配见 {@link PredictionConfig}，调用见 {@link PredictionService}。
 */
public class PrecisePredictionEngine implements ResidualSampleSource {

    private static final Logger log = LoggerFactory.getLogger(PrecisePredictionEngine.class);

    /** 快照 {@code zSeries} 保留的新息条数（E 组累积判据窗长 32，够用且不膨胀快照）。 */
    static final int Z_SERIES_CAP = 32;

    /** 建参考线时相邻顶点的最小间距，m：折线零长段无方向，必须去重。 */
    static final double MIN_TRACK_GAP_M = 5.0;

    /**
     * 实测轨迹<b>跨度</b>足以压住位置噪声的门限，m；不足则退回船舶自报航向定参考线走向。
     *
     * <p><b>为什么需要它（实测缺陷，2026-10-27）</b>：M1 仿真器按 1 Hz 发 AIS、8 节 ⇒ 每拍真实
     * 位移只有 4.1 m，而位置噪声是 {@code ±0.00005°}（每轴 ±5.5 m）——<b>单拍位移小于噪声</b>。
     * 冷启动只等两拍、用这 4 m 的基线定参考线走向，等于让噪声定走向：实测 200 个随机种子，
     * 走向误差中位 25.9°、P90 73.3°、最大 174.6°；其中 8% 的种子方向直接反了，
     * {@code v_s = sog·cos(cog − 走向)} 变成负值，弧长往回走被夹在折线端点，
     * 30 个预测点全挤在起点 20 m 内（观感就是「船不动了」），而 σ 照常增长、
     * 结构断言与地理框都拦不住——<b>静默错误</b>，下游会面点会按「船不动」求解。
     *
     * <p><b>处置</b>：跨度不足时不用实测走向，改用船舶自报的对地航向 {@code cog} 建一条直线参考线。
     * {@code cog} 自身误差是「度」级（仿真里是精确值），比 4 m 基线 + 5.5 m 噪声强得多；
     * 而跨度一旦够（窗口累积到 60 m 以上），仍用<b>首→末</b>实测走向——那时它比 {@code cog} 准。
     * 这样冷启动时机不变（第 2 拍即出预测），只是走向换了更可靠的来源。
     */
    static final double ROBUST_BEARING_MIN_SPAN_M = 60.0;

    /**
     * 信任船舶自报航向所需的最低对地航速，kn（MED-BUG.42）。
     *
     * <p><b>为什么需要速度门</b>：COG 是位移矢量的方向。船速降到 1 kn 以下时，
     * 1 Hz 采样下的真实位移只有 0.5 m，而 AIS 位置量化与噪声是米级——位移被噪声淹没，
     * COG 退化成随机量。用一条随机方向的参考线去做预测，所有残差与 σ 都建立在这个假方向上，
     * 属于<b>静默错误</b>（结构断言与地理框都拦不住）。
     *
     * <p>取 1.0 kn（≈0.51 m/s）：这是 AIS 里「船在动」的最小可信量级，也是港内缓速的上沿之一；
     * 低于它时"宁可这一拍不给预测，等速度起来再建线"，与冷启动「点不够就不出预测」同口径。
     */
    static final double COG_TRUST_MIN_SOG_KN = 1.0;

    /**
     * AIS 「不可用」哨兵值：对地航速 1023（0.1 kn 单位）＝ {@value} kn（MED-BUG.43）。
     *
     * <p>原始 AIS 报文里 SOG 以 0.1 kn 为单位、1023 表示「不可用」。上游若按十进制直接换算，
     * 落在这里就是一个看起来正常的 102.3 kn；不拦就会当成真实速度去做预测，
     * 船在 1 s 内"跑"出 52 m，滤波器的速度状态被这一帧打歪，此后几十拍的 s 都偏。
     */
    static final double AIS_SOG_UNAVAILABLE_KN = 102.3;

    /**
     * AIS 「不可用」哨兵值：对地航向 3600（0.1° 单位）＝ {@value}°（MED-BUG.43）。
     *
     * <p>航向「不可用」在 AIS 里还有另一种表达：真航向 511（整度单位）。两种哨兵都落在
     * {@code [0, 360)} 之外或边界上，故本类统一按「COG 必须落在 {@code [0, 360)}」判——
     * 360.0 与 511.0 一并被拒，不必为两种编码各写一条分支。
     */
    static final double AIS_COG_UNAVAILABLE_DEG = 360.0;

    /** 线尾余量，m：投影逼近折线末端时重建参考线。 */
    static final double TAIL_MARGIN_M = 100.0;

    /**
     * 每船缓存的残差样本条数上限（1 Hz 输入下约 4 分钟）。
     *
     * <p>为什么要有上限：消费方（校准）默认 60 s 才来取一次，正常只积几十条；
     * 但节流配置被改大、或校准整体不可用时，缓存会无界增长。上限取 256 是
     * 「远大于正常积压、又小到不会撑爆内存」的折中——真被压满说明链路有病，
     * 丢最旧的样本比 OOM 好。
     */
    static final int RESIDUAL_CAP = 256;

    /** 残差样本保留期相对预测时域的余量，s（时域外的观测算不出残差，留着也无用）。 */
    private static final long RESIDUAL_RETENTION_MARGIN_SEC = 60L;

    /**
     * 预测探针的撒点间隔，s。
     *
     * <p>取值理由：1 Hz 输入下每拍一支会让待评队列压到 1800 支、产出 3600 条/时/船，
     * 远超 200 条的采信门槛且毫无必要；30 s 一支则是 120 条/时/船，攒满 200 条约 100 分钟，
     * 与「跑一批 AIS 看校准是否收敛」的节奏吻合。取 60 s 会让攒满门槛需要 3 小时以上。
     */
    static final int PROBE_INTERVAL_SEC = 30;

    /**
     * 探针评估时距栅格，s——τ 在采样时<b>轮转</b>取这些值。
     *
     * <p>为什么必须轮转：若每支探针都只评「最近的一版」，τ 会恒等于 1 s，样本全落在
     * 最小的时域桶里，σ 的增长项 α 永远得不到检验——而 α 正是带宽里最需要标定的参数
     * （11-02 实测报告：σ∥ 的斜率是真实误差增长率的 8 倍）。
     *
     * <p>栅格从 60 s 铺到 1800 s（＝缺省时域），覆盖「近距准、远距松」的全过程；
     * 时域配得比栅格最大值小时，超出的档位会被裁到 {@code horizonSec}。
     */
    static final int[] PROBE_LAG_GRID_SEC = {60, 180, 300, 600, 900, 1200, 1800};

    /** 待评估探针上限（每支带 31 个点，64 支约覆盖 32 分钟不评估的极端情况）。 */
    static final int PROBE_CAP = 64;

    /** 参考线延伸长度在时域覆盖之外的额外余量，m。 */
    private static final double EXTENSION_MARGIN_M = 500.0;

    /**
     * 偏置生效的船速下限，m/s（≈0.2 kn）。
     *
     * <p>低于此速度视为静止：偏置是「预测里程系统性偏差」的修正量，对停泊／锚泊船没有意义，
     * 照加会把静止的船预测成漂移。取 0.1 与 AIS 的 {@code sog} 分辨率（0.1 kn）同量级。
     */
    static final double ALONG_BIAS_MIN_SPEED_MS = 0.1;

    /**
     * 引擎参数（{@code 80} 附录 B 的建议初值；{@code sys_param} 未登记这些键，按纪律不臆造键名）。
     *
     * @param laneHalfWidthM      航道半宽，m（{@code σ_cross} 截断上界，同时是 {@code e} 的夹紧范围）
     * @param lTolM               纵向容忍上限，m（有效期判定）
     * @param wTolM               横向容忍上限，m（有效期判定）
     * @param refColdStartSec     冷启动参考窗长度，s（退化 1 用最近这一段实测轨迹建参考线）
     * @param q                   过程噪声谱密度，{@code m²/s³}（KF 与预测协方差共用同一值）
     * @param thetaMaxDeg         观测夹角截断上界，度
     * @param vMaxCraftMs         船速限幅，m/s
     * @param initialVelocityStdMs 初始化纵向速度标准差，m/s
     * @param tauESec             横向回复时间常数，s
     */
    public record Params(
            double laneHalfWidthM,
            double lTolM,
            double wTolM,
            double refColdStartSec,
            double q,
            double thetaMaxDeg,
            double vMaxCraftMs,
            double initialVelocityStdMs,
            double tauESec) {

        public Params {
            if (!(laneHalfWidthM > 0.0) || !(lTolM > 0.0) || !(wTolM > 0.0)
                    || !(refColdStartSec > 0.0) || !(q > 0.0) || !(vMaxCraftMs > 0.0)
                    || !(initialVelocityStdMs > 0.0) || !(tauESec > 0.0)) {
                throw new IllegalArgumentException("引擎参数必须为正值：" + laneHalfWidthM + "/"
                        + lTolM + "/" + wTolM + "/" + refColdStartSec + "/" + q + "/"
                        + vMaxCraftMs + "/" + initialVelocityStdMs + "/" + tauESec);
            }
            if (!(thetaMaxDeg > 0.0) || thetaMaxDeg >= 90.0) {
                throw new IllegalArgumentException("thetaMaxDeg 须在 (0, 90) 内：" + thetaMaxDeg);
            }
        }

        /** 附录 B 建议初值。 */
        public static Params defaults() {
            return new Params(50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0);
        }
    }

    /**
     * 一版精算预测的产出。
     *
     * @param mmsi        船号
     * @param t0Ms        预测起点时刻，Unix 毫秒（＝触发它的那条观测的时刻）
     * @param courseDeg   主轴方位（该处参考线走向），度
     * @param validitySec 有效期，s（{@code §3.2.8}：σ 超容忍上限的最早时距）
     * @param premise     前提有效性
     * @param points      预测点列（含 τ=0）
     * @param zSeries     近若干拍的归一化新息（供 E 组／标定侧消费）
     */
    public record Outcome(
            String mmsi,
            long t0Ms,
            double courseDeg,
            long validitySec,
            PremiseStatus premise,
            List<EstimatedPoint> points,
            List<Double> zSeries) {

        public Outcome {
            points = List.copyOf(points);
            zSeries = List.copyOf(zSeries);
        }

        /** 最后一个预测点的时刻，Unix 毫秒。 */
        public long lastTMs() {
            return points.isEmpty() ? t0Ms : points.get(points.size() - 1).tMs();
        }
    }

    /**
     * 状态快照（只读投影，供单测与联调观察「不确定度是否随观测收缩」）。
     *
     * @param observationCount      窗口内观测条数
     * @param initialized           滤波器是否已初始化
     * @param posteriorVarianceS    最近一次<b>更新后</b>的沿航道方差 {@code P_ss}，{@code m²}
     * @param predictedVarianceS    最近一次<b>预测步后</b>（更新前）的方差，{@code m²}
     * @param normalizedInnovation  最近一次归一化新息
     */
    public record StateView(
            int observationCount,
            boolean initialized,
            double posteriorVarianceS,
            double predictedVarianceS,
            double normalizedInnovation) {
    }

    /** 每船状态。 */
    private static final class ShipState {

        final Deque<AisMessage> window = new ArrayDeque<>();

        final Deque<Double> zSeries = new ArrayDeque<>();

        AlongTrackKalmanFilter along;

        LateralOuFilter lateral;

        WaterwayGeometry geometry;

        PremiseMonitor premise;

        NavPhaseTracker navPhase;

        PremiseStatus premiseStatus = PremiseStatus.VALID;

        boolean initialized;

        long lastAppliedTsMs = Long.MIN_VALUE;

        double predictedVarianceS;

        double normalizedInnovation;

        // ── 残差出口（11-03 D1）──

        /** 已撒出、尚待评估的预测探针（按评估时刻升序）。 */
        final Deque<PredictionProbe> probes = new ArrayDeque<>();

        /** 下一次可以撒探针的时刻，Unix 毫秒（首条观测时为 {@link Long#MIN_VALUE}）。 */
        long nextProbeTsMs = Long.MIN_VALUE;

        /** 探针时距轮转下标（让样本覆盖整条 τ 栅格，而不是全挤在最小 τ 上）。 */
        int probeCursor;

        /** 服务层为下一版预测登记的版本号（由 {@link #recordVersion} 写入）。 */
        long boundVersion;

        /** 最近产生的残差样本（sampleTs 严格递增，见 {@link #emitResidual}）。 */
        final Deque<ResidualSample> residuals = new ArrayDeque<>();
    }

    /**
     * 一支「预测探针」：把某时刻的一版预测留到将来再评。
     *
     * <p>为什么不能只跟「上一拍的预测」比：观测是 1 Hz、预测每拍都刷新，于是
     * {@code τ = t_obs − t0} 恒为 1 s。那样采出来的样本全落在最小的时域桶里，
     * <b>σ 的增长项 α 永远得不到检验</b>——而 α 恰恰是带宽里最需要标定的参数
     * （11-02 的实测报告里 σ∥ 斜率是真实误差增长率的 8 倍）。故按固定间隔留一版预测，
     * 到点再评，τ 才能覆盖到小时级。
     *
     * @param t0Ms    预测起点时刻
     * @param version 该版预测的快照版本号
     * @param evalAtMs 预定评估时刻（{@code t0Ms + τ}）
     * @param tauSec   预定评估时距，s（栅格值；实际 τ 以评估时的真实时刻回算）
     * @param samples  该版预测点列（带 s/e/σ/切向）
     * @param geometry 该版预测所用的参考线（换线后 s/e 不再可比）
     */
    private record PredictionProbe(
            long t0Ms,
            long version,
            long evalAtMs,
            int tauSec,
            List<MultiStepPredictor.Sample> samples,
            WaterwayGeometry geometry) {
    }

    private final PredictionSettings settings;

    private final Params params;

    /** 航道中心线；非空时参考线固定取中心线，不再由本船实测窗口建线。 */
    private final WaterwayGeometry centerline;

    /** 航段分段限速查询；缺省恒不限速（等价 {@code unbounded()}）。 */
    private final SpeedLimitLookup speedLimit;

    /**
     * 沿航道速度偏置的取值口（{@code sys_param: predAlongSpeedBiasMs}，m/s，77 §8 G1）。
     *
     * <p><b>为什么是 {@link DoubleSupplier} 而不是一个 {@code double}</b>：校准器会在运行期
     * 回写这个键（{@link PredictionCalibrationService} 写库并同步 {@code SysParamLoader} 缓存），
     * 而引擎是常驻单例。构造时读一次等于把偏置冻结在启动那一刻——校准写进去的值永远不生效，
     * 「闭环」又断在同一个地方，只是这次断得看不出来（数值不动，日志也不报错）。
     * 每次预测时取值，才能让回写即时生效。
     *
     * <p><b>为什么由模块层注入而不是引擎自己读表</b>：算法层「不读配置、不认键名」是分层纪律
     * （{@code PredictionParams} 类注释），引擎是模块层适配器，但它不该认识
     * {@code SysParamLoader}——注入取值口后，引擎只要求「给我一个数」，认键名的事留在
     * {@link PredictionConfig} 一处。
     */
    private final DoubleSupplier alongSpeedBiasMs;

    private final Map<String, ShipState> states = new ConcurrentHashMap<>();

    public PrecisePredictionEngine(PredictionSettings settings, Params params) {
        this(settings, params, null);
    }

    public PrecisePredictionEngine(PredictionSettings settings, Params params,
            WaterwayGeometry centerline) {
        this(settings, params, centerline, SpeedLimitLookup.unbounded());
    }

    /**
     * @param settings   预测参数
     * @param params     引擎参数
     * @param centerline 航道中心线；为 {@code null} 时沿用实测窗口建参考线的退化口径
     * @param speedLimit 航段分段限速查询；为 {@code null} 时恒不限速
     */
    public PrecisePredictionEngine(PredictionSettings settings, Params params,
            WaterwayGeometry centerline, SpeedLimitLookup speedLimit) {
        this(settings, params, centerline, speedLimit, null);
    }

    /**
     * @param settings           预测参数
     * @param params             引擎参数
     * @param centerline         航道中心线；为 {@code null} 时沿用实测窗口建参考线
     * @param speedLimit         航段分段限速查询；为 {@code null} 时恒不限速
     * @param alongSpeedBiasMs   沿航道速度偏置取值口（{@code predAlongSpeedBiasMs}）；
     *                           为 {@code null} 或返回 0 时行为与接入前完全一致
     */
    public PrecisePredictionEngine(PredictionSettings settings, Params params,
            WaterwayGeometry centerline, SpeedLimitLookup speedLimit,
            DoubleSupplier alongSpeedBiasMs) {
        if (settings == null || params == null) {
            throw new IllegalArgumentException("settings / params 不得为 null");
        }
        this.settings = settings;
        this.params = params;
        this.centerline = centerline;
        this.speedLimit = speedLimit == null ? SpeedLimitLookup.unbounded() : speedLimit;
        this.alongSpeedBiasMs = alongSpeedBiasMs == null ? () -> 0.0 : alongSpeedBiasMs;
    }

    /** 已在跟踪的船数。 */
    public int trackedShipCount() {
        return states.size();
    }

    /** 取某船状态（未跟踪时为空）。 */
    public Optional<StateView> stateOf(String mmsi) {
        ShipState state = states.get(mmsi);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.of(new StateView(
                    state.window.size(),
                    state.initialized,
                    state.along == null ? 0.0 : state.along.varianceS(),
                    state.predictedVarianceS,
                    state.normalizedInnovation));
        }
    }

    /**
     * 取该船当前可用的残差样本（11-03 D1）。
     *
     * <p>返回的是<b>缓存最近一窗</b>的不可变副本，按 {@code sampleTsMs} 升序。
     * 重复调用不改变任何状态，也不会重复产生样本——样本只在 {@link #predict} 推进状态时产生，
     * 满足接口「幂等可重入」的约定。
     *
     * <p>未跟踪的船、冷启动未完成的船、或刚换过参考线的船都返回空列表（不抛异常）。
     * 消费方（{@code PredictionCalibrationService}）把空列表当「本拍无新样本」处理。
     *
     * @param mmsi 目标船标识
     * @return 升序样本列表；无样本时为空列表
     */
    @Override
    public List<ResidualSample> residualSamplesOf(String mmsi) {
        if (mmsi == null || mmsi.isBlank()) {
            return List.of();
        }
        ShipState state = states.get(mmsi);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.residuals);
        }
    }

    /**
     * 服务层登记「下一版预测将使用的快照版本号」。
     *
     * <p><b>为什么由服务层告知而不是本类自己编号</b>：版本号是
     * {@code PredictionSnapshot.version}，由 {@link PredictionService} 的每船计数器发放，
     * 中间还夹着「匀速版回落」「断言未过」等分支——引擎自己数一份必然与快照版本错位，
     * 而 {@code pred_error_sample.pred_version} 的语义就是「这一版快照」。
     *
     * <p>调用点必须在 {@link #predict} <b>之前</b>：这样本版点列在产生时就能盖上自己的版本号，
     * 下一拍配对残差时直接取用，不需要在残差里再猜版本。
     *
     * <p>取值只增不减：重算路径可能带着同一条观测再来一次，若允许回退，
     * 已产生的样本会被打上更旧的版本，四元组唯一键就失去意义。
     *
     * @param mmsi    目标船
     * @param version 本版快照版本号
     */
    public void recordVersion(String mmsi, long version) {
        if (mmsi == null || mmsi.isBlank() || version <= 0L) {
            return;
        }
        ShipState state = states.computeIfAbsent(mmsi, key -> new ShipState());
        synchronized (state) {
            if (version > state.boundVersion) {
                state.boundVersion = version;
            }
        }
    }

    /**
     * 每隔一段时间撒一支探针，把这一版预测留到 {@code t0 + τ} 再评。
     *
     * <p>为什么不是每拍都撒：1 Hz 下每拍一支，待评队列要压 1800 支 × 31 个点，
     * 而产出量是 3600 条/时/船——远超 200 条门槛，纯属浪费库与内存。
     * 30 s 一支、每支只评一次，产出 120 条/时/船：攒满 200 条约 100 分钟，
     * 与「跑一批 AIS 看校准是否收敛」的节奏吻合。
     *
     * <p>τ 轮转的理由见 {@link PredictionProbe}：不轮转就全挤在最小 τ 上，
     * σ 的增长项永远得不到检验。
     */
    private void scheduleProbe(ShipState state, Computed computed) {
        long t0Ms = computed.outcome().t0Ms();
        if (state.nextProbeTsMs != Long.MIN_VALUE
                && t0Ms < state.nextProbeTsMs) {
            return;
        }
        int horizonSec = (int) Math.min(settings.horizonSec(), Integer.MAX_VALUE);
        int lagSec = Math.min(PROBE_LAG_GRID_SEC[state.probeCursor % PROBE_LAG_GRID_SEC.length], horizonSec);
        state.probeCursor++;

        long evalAtMs = t0Ms + lagSec * 1000L;
        state.probes.addLast(new PredictionProbe(
                t0Ms,
                state.boundVersion,
                evalAtMs,
                lagSec,
                computed.samples(),
                state.geometry));
        state.nextProbeTsMs = t0Ms + PROBE_INTERVAL_SEC * 1000L;

        // 上限保护：船长时间不上报时探针会一直积压（每支都带着 31 个点）
        while (state.probes.size() > PROBE_CAP) {
            state.probes.removeFirst();
        }
    }

    /**
     * 把到点的探针与本拍观测配对，产出残差样本。
     *
     * <p><b>坐标口径</b>：残差在参考线的曲线坐标 {@code (s, e)} 里算，不在经纬度里算。
     * 因为 NEES 的分母是「沿航道 σ∥ × 横向 σ⊥」，分子也必须是同一组坐标轴上的分量；
     * 用经纬度差再旋回航道坐标系，等于把参考线走向误差也算进残差。
     *
     * <p><b>时域内插</b>：预测点步长是 {@code stepSec}（默认 60 s），而评估时刻几乎不可能
     * 正好落在点上，故按时刻在相邻两点间线性内插 {@code s}/{@code e}/σ；切向按最短弧内插
     * （直接线性内插会在 350°→10° 处算出 180° 的假转向）。
     *
     * <p><b>直接丢弃探针的四种情况（不产样本、不记错）</b>：
     * <ol>
     *   <li>参考线换过（{@code probe.geometry != state.geometry}）——换线后 {@code s} 的原点变了，
     *       两版 {@code s} 相减没有物理意义，硬算出来的是一笔假残差；</li>
     *   <li>观测时刻不在「(t0, 末点]」区间内——τ ≤ 0 无信息量，超出时域则预测没覆盖那一刻；</li>
     *   <li>内插出的 σ 非正（数值退化）——{@link ResidualSample} 会拒收，这里提前挡掉；</li>
     *   <li>构造样本抛参数异常（非有限观测）——残差是增强项，绝不能打断预测。</li>
     * </ol>
     */
    private void evaluateDueProbes(ShipState state, AisMessage observation) {
        long tObs = observation.ts();
        WaterwayGeometry.Projection measured = null;

        while (!state.probes.isEmpty()) {
            PredictionProbe probe = state.probes.peekFirst();
            if (probe.evalAtMs() > tObs) {
                // 队列按评估时刻升序：队头还没到点，后面的更不会到
                break;
            }
            state.probes.removeFirst();
            if (probe.geometry() != state.geometry) {
                continue;
            }
            List<MultiStepPredictor.Sample> samples = probe.samples();
            if (samples.size() < 2) {
                continue;
            }
            MultiStepPredictor.Sample first = samples.get(0);
            MultiStepPredictor.Sample last = samples.get(samples.size() - 1);
            if (tObs <= first.tMs() || tObs > last.tMs()) {
                continue;
            }

            MultiStepPredictor.Sample before = first;
            MultiStepPredictor.Sample after = samples.get(1);
            for (int index = 1; index < samples.size(); index++) {
                if (samples.get(index).tMs() >= tObs) {
                    after = samples.get(index);
                    before = samples.get(index - 1);
                    break;
                }
            }
            long spanMs = after.tMs() - before.tMs();
            if (spanMs <= 0L) {
                continue;
            }
            double fraction = (tObs - before.tMs()) / (double) spanMs;

            double predictedS = lerp(before.s(), after.s(), fraction);
            double predictedE = lerp(before.e(), after.e(), fraction);
            double sigmaAlong = lerp(before.sigmaAlongM(), after.sigmaAlongM(), fraction);
            double sigmaCross = lerp(before.sigmaCrossM(), after.sigmaCrossM(), fraction);
            if (!(sigmaAlong > 0.0) || !(sigmaCross > 0.0)) {
                continue;
            }
            double predictedCourse = before.courseDeg()
                    + signedArcDeg(before.courseDeg(), after.courseDeg()) * fraction;
            if (measured == null) {
                measured = state.geometry.project(observation.lon(), observation.lat());
            }

            emitResidual(state, observation, probe.version(), tObs,
                    (int) Math.round((tObs - probe.t0Ms()) / 1000.0),
                    measured, predictedS, predictedE, predictedCourse, sigmaAlong, sigmaCross);
        }
    }

    /** 组装并缓存一条残差样本（符号口径见 {@link ResidualSample}）。 */
    private void emitResidual(ShipState state, AisMessage observation, long version, long tObs,
            int tauSec, WaterwayGeometry.Projection measured, double predictedS, double predictedE,
            double predictedCourse, double sigmaAlong, double sigmaCross) {
        ResidualSample sample;
        try {
            sample = new ResidualSample(
                    observation.mmsi(),
                    version,
                    tauSec,
                    measured.s() - predictedS,
                    measured.e() - predictedE,
                    signedArcDeg(predictedCourse, observation.cog()),
                    sigmaAlong,
                    sigmaCross,
                    state.along == null ? 0.0 : state.along.vAlongMs(),
                    tObs);
        } catch (IllegalArgumentException malformed) {
            log.debug("残差样本不合口径，已跳过：mmsi={} 原因={}", observation.mmsi(), malformed.getMessage());
            return;
        }

        state.residuals.addLast(sample);
        long cutoffMs = tObs - (settings.horizonSec() + RESIDUAL_RETENTION_MARGIN_SEC) * 1000L;
        while (!state.residuals.isEmpty()
                && (state.residuals.size() > RESIDUAL_CAP
                        || state.residuals.peekFirst().sampleTsMs() < cutoffMs)) {
            state.residuals.removeFirst();
        }
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    /**
     * 两个方位间的<b>带符号</b>最短弧，度，取值 {@code [-180, 180)}（{@code to − from}）。
     *
     * <p>与 {@code PredictionService.shortestArcDeg} 的区别：那个返回绝对值，用于判「航向跳变
     * 是否超阈值」，只关心幅度；残差要的是<b>方向</b>——偏左偏右对偏差校准器是不同的修正，
     * 取绝对值会把系统性左偏抹成随机误差。
     */
    static double signedArcDeg(double fromDeg, double toDeg) {
        double delta = ConstantVelocityPredictor.normalizeDeg(toDeg)
                - ConstantVelocityPredictor.normalizeDeg(fromDeg);
        return ((delta % 360.0) + 540.0) % 360.0 - 180.0;
    }

    /**
     * 用一条观测推进该船状态并给出新一版预测。
     *
     * <p><b>空返回只有一种情况</b>（调用方据此回落匀速版，不得当异常）：
     * <b>冷启动未完成</b>——窗口里还凑不出两个间距 ≥ {@value #MIN_TRACK_GAP_M} m 的实测点，
     * 建不出参考折线。</p>
     *
     * <p><b>迟到／重复的观测</b>不推进状态（时间不能倒流），但仍返回<b>当前状态</b>的一版预测，
     * 时间锚取已推进到的时刻：F3 的重算正是用同一条观测再来一次，若这里返回空，
     * 精算模式下的重算就会悄悄退化成匀速版。</p>
     *
     * @param observation AIS 船位（{@code sog} 为节、{@code cog} 为度）
     * @return 新一版预测；尚不可精算时为空
     * @throws IllegalArgumentException 观测越界（与匀速版同一口径，由调用方丢弃）
     */
    public Optional<Outcome> predict(AisMessage observation) {
        validate(observation);

        ShipState state = states.computeIfAbsent(observation.mmsi(), key -> new ShipState());

        synchronized (state) {
            if (!append(state, observation)) {
                // 迟到／重复观测：不推进状态，按已推进到的时刻给一版预测（见方法注释）
                if (!state.initialized) {
                    return Optional.empty();
                }
                return Optional.of(predictFromState(state, state.lastAppliedTsMs, observation.mmsi())
                        .outcome());
            }

            if (state.geometry == null) {
                WaterwayGeometry geometry = centerline != null
                        ? centerline
                        : buildGeometry(state.window, observation);
                if (geometry == null) {
                    log.debug("精算冷启动：mmsi={} 实测点不足以建参考线（{} 条）",
                            observation.mmsi(), state.window.size());
                    return Optional.empty();
                }
                state.geometry = geometry;
            }

            if (!state.initialized) {
                // 冷启动：把窗口里的实测点按时间顺序补进滤波器（第一帧初始化，其后逐拍 update）
                for (AisMessage historic : state.window) {
                    applyObservation(state, historic);
                }
            } else if (observation.ts() > state.lastAppliedTsMs) {
                applyObservation(state, observation);
                // 评估到点的探针必须放在 applyObservation <b>之后</b>：v_along 取的是后验速度估计。
                // 探针里存的是「过去某一版的点列」，与本次状态推进无关，先后顺序不影响残差本身。
                evaluateDueProbes(state, observation);
            }
            // 时标相同或更旧的观测：不推进状态，直接按当前状态出预测（F3 的重算就走这条）

            if (!state.initialized) {
                return Optional.empty();
            }

            if (centerline == null && state.along.s() >= state.geometry.totalLengthM() - TAIL_MARGIN_M) {
                log.warn("参考线将尽，重建并重锚：mmsi={} s={} 总长={}",
                        observation.mmsi(), state.along.s(), state.geometry.totalLengthM());
                WaterwayGeometry rebuilt = buildGeometry(state.window, observation);
                if (rebuilt == null) {
                    // HIGH.21：重建失败时<b>不能</b>继续用那条已走完的参考线出预测。
                    // 继续下走的后果不是"精度差一点"，而是 s 被夹在 totalLength 上，
                    // 于是整段点列冻在同一个坐标、却带着正常的 σ 与前提状态发给下游——
                    // 下游（会面点求解、ETA）无法把它和"船真的停住了"区分开。
                    // 本类对"这一拍给不出可信预测"的既有口径就是不产出（见冷启动分支），
                    // 故这里同样返回空，让快照停在上一个有效版本上，而不是刷一版假的。
                    log.warn("参考线已尽且无法重建，本拍不出预测：mmsi={} s={} 总长={} 窗口={} 条",
                            observation.mmsi(), state.along.s(), state.geometry.totalLengthM(),
                            state.window.size());
                    return Optional.empty();
                }
                state.geometry = rebuilt;
                state.initialized = false;
                state.lastAppliedTsMs = Long.MIN_VALUE;
                for (AisMessage historic : state.window) {
                    applyObservation(state, historic);
                }
            }

            Computed computed = predictFromState(state, observation.ts(), observation.mmsi());
            scheduleProbe(state, computed);
            return Optional.of(computed.outcome());
        }
    }

    // ───────────── 内部：状态推进 ─────────────

    /**
     * 把观测压入窗口（按时间升序去重）。
     *
     * @return 是否需要继续本次推进；迟到／重复观测返回 {@code false} 且不入窗
     */
    private boolean append(ShipState state, AisMessage observation) {
        AisMessage latest = state.window.peekLast();
        if (latest != null && observation.ts() <= latest.ts()) {
            log.debug("观测时标未前进，跳过：mmsi={} ts={} 窗口末条={}",
                    observation.mmsi(), observation.ts(), latest.ts());
            return false;
        }
        state.window.addLast(observation);
        trimWindow(state, observation.ts());
        return true;
    }

    /** 窗口只留最近 {@code refColdStartSec} 的观测，且至少保留两条（参考线需要）。 */
    private void trimWindow(ShipState state, long nowTsMs) {
        while (state.window.size() > 2) {
            AisMessage oldest = state.window.peekFirst();
            if ((nowTsMs - oldest.ts()) / 1000.0 > params.refColdStartSec()) {
                state.window.removeFirst();
            } else {
                break;
            }
        }
    }

    /** 用一条观测推进滤波器：首帧初始化，其后 predict ＋ update。 */
    private void applyObservation(ShipState state, AisMessage observation) {
        WaterwayGeometry.Projection projection = state.geometry.project(observation.lon(), observation.lat());
        double courseDeg = state.geometry.courseDegAt(projection.s());
        double sogMs = ConstantVelocityPredictor.knotsToMs(observation.sog());

        if (!state.initialized) {
            state.along = new AlongTrackKalmanFilter(params.q(), settings.sigma0AlongM(),
                    params.thetaMaxDeg(), params.vMaxCraftMs(), params.initialVelocityStdMs());
            state.lateral = new LateralOuFilter(lateralParams());
            state.along.initialize(projection.s(), Math.abs(sogMs), observation.cog(), courseDeg);
            state.lateral.initialize(projection.e(), observation.ts());
            state.initialized = true;
            state.lastAppliedTsMs = observation.ts();
            state.predictedVarianceS = state.along.varianceS();
            advancePremise(state, observation);
            return;
        }

        double deltaTSec = (observation.ts() - state.lastAppliedTsMs) / 1000.0;
        if (!(deltaTSec > 0.0)) {
            return;
        }

        state.along.predict(deltaTSec);
        // 预测步后的方差（更新前）留档：这是「有新观测进入时不确定度收缩」的对照基线
        state.predictedVarianceS = state.along.varianceS();

        double thetaRad = Math.toRadians(AlongTrackKalmanFilter.shortestAngleDiffDeg(
                observation.cog(), courseDeg));
        state.along.update(projection.s(), thetaRad);
        state.lateral.update(projection.e(), observation.ts());
        state.lastAppliedTsMs = observation.ts();
        advancePremise(state, observation);

        double nis = state.along.normalizedInnovation();
        if (Double.isFinite(nis)) {
            state.normalizedInnovation = nis;
            if (state.zSeries.size() >= Z_SERIES_CAP) {
                state.zSeries.removeFirst();
            }
            state.zSeries.addLast(nis);
        }
    }

    /**
     * 推进前提监测：把本帧观测送入 {@link PremiseMonitor}，并记录最新结论。
     *
     * <p>首帧按速度符号锁定方向参考，之后由监测器自行累积偏航与翻转判据。
     */
    private void advancePremise(ShipState state, AisMessage observation) {
        if (state.premise == null) {
            state.premise = new PremiseMonitor(PremiseMonitor.Params.defaults(),
                    state.along.vAlongMs() >= 0.0 ? 1 : -1, List.of());
            state.premise.lockCourseRef(state.geometry.courseDegAt(state.along.s()));
        }
        if (state.navPhase == null) {
            state.navPhase = new NavPhaseTracker(NavPhaseTracker.Params.defaults());
        }
        NavPhaseTracker.Phase phase = state.navPhase.update(new NavPhaseTracker.Observation(
                observation.ts(), state.along.s(), state.along.vAlongMs(), params.vMaxCraftMs(), false));
        state.premiseStatus = state.premise.observe(new PremiseMonitor.Observation(
                observation.ts(), state.along.s(), state.along.vAlongMs(), observation.cog(),
                phase, false, false)).verdict().toStatus();
    }

    // ───────────── 内部：参考线与预测 ─────────────

    /**
     * 由实测窗口建参考折线（退化 1）。
     *
     * <p>先用 {@link AisObservationFilter} 清洗<b>建线用的点</b>；清洗后不足两点则退回原始窗口
     * （清洗只影响参考线质量，不得让整条链路停摆——滤波链本身不因单条被清洗点停拍）。
     *
     * <p>走向按窗口跨度二选一：跨度 ≥ {@value #ROBUST_BEARING_MIN_SPAN_M} m 时用<b>首→末</b>实测走向；
     * 不足时改用 {@code reportedCogDeg} 建直线参考线（理由见该常量）。
     *
     * <p><b>MED-BUG.42：自报航向不是无条件可信的</b>。COG 是「对地航向」，物理上它是
     * 位移矢量的方向；船近乎静止时位移被位置噪声淹没，船端算出的 COG 就退化成随机量
     * （AIS 规范在这种情况下甚至会直接把 COG 填成不可用哨兵）。原实现只要有两条点就
     * 拿 COG 建线，于是<b>一条泊船会被赋予一条随机方向的参考线</b>，此后所有残差都在
     * 这条假线上算——比"这一拍不给预测"糟得多。
     *
     * <p>故加一道速度门：末条观测的 SOG 低于 {@value #COG_TRUST_MIN_SOG_KN} kn 时，
     * 自报航向无信息量，直接判为「建不出参考线」（返回 {@code null}），由调用方按
     * 无参考线处置；速度恢复后下一拍自然重建成功。
     *
     * @param window         实测窗口（按时间升序）
     * @param latest         窗口末条观测（取自报航向与其 SOG；跨度不足时的走向来源）
     * @return 参考折线；实测点不足两点、或跨度不足且自报航向不可信时为空
     */
    private WaterwayGeometry buildGeometry(Deque<AisMessage> window, AisMessage latest) {
        List<AisMessage> raw = new ArrayList<>(window);
        List<AisMessage> cleaned = cleanForGeometry(raw);

        List<WaterwayGeometry.Vertex> vertices = toVertices(cleaned);
        if (vertices.size() < 2) {
            vertices = toVertices(raw);
        }
        if (vertices.size() < 2) {
            return null;
        }

        WaterwayGeometry.Vertex first = vertices.get(0);
        WaterwayGeometry.Vertex last = vertices.get(vertices.size() - 1);
        double spanM = planarDistanceM(first, last);

        if (spanM < ROBUST_BEARING_MIN_SPAN_M) {
            // 跨度压不住位置噪声（1 Hz、8 节下位移 4.1 m < 噪声 ±5.5 m）：
            // 实测走向无信息量，改用自报航向建一条直线参考线。见 ROBUST_BEARING_MIN_SPAN_M。
            double reportedSogKn = latest == null ? 0.0 : latest.sog();
            if (!(reportedSogKn >= COG_TRUST_MIN_SOG_KN)) {
                log.debug("参考线跨度不足（{} m）且船速仅 {} kn < {} kn，自报航向不可信，暂不建线",
                        String.format(java.util.Locale.ROOT, "%.1f", spanM),
                        String.format(java.util.Locale.ROOT, "%.2f", reportedSogKn),
                        COG_TRUST_MIN_SOG_KN);
                return null;
            }
            double bearingDeg = ConstantVelocityPredictor.normalizeDeg(latest.cog());
            log.debug("参考线跨度不足（{} m < {} m），走向改取自报航向 {}°",
                    String.format(java.util.Locale.ROOT, "%.1f", spanM),
                    ROBUST_BEARING_MIN_SPAN_M, String.format(java.util.Locale.ROOT, "%.1f", bearingDeg));
            List<WaterwayGeometry.Vertex> straight = new ArrayList<>(3);
            straight.add(first);
            // 10 m 的短线段只为给出方向与一个正的段长（零点长段非法），随后接预测时域延伸段
            WaterwayGeometry.Vertex anchor = offsetM(first, bearingDeg, Math.max(spanM, 10.0));
            straight.add(anchor);
            straight.add(offsetM(anchor, bearingDeg, extensionM()));
            return new WaterwayGeometry(straight);
        }

        // 跨度够大时改用窗口首→末定走向，而不是末两个顶点：同一段轨迹上跨度越大，噪声占比越小
        double bearingDeg = ConstantVelocityPredictor.bearingDeg(
                first.lon(), first.lat(), last.lon(), last.lat());
        vertices.add(offsetM(last, bearingDeg, extensionM()));
        return new WaterwayGeometry(vertices);
    }

    private List<AisMessage> cleanForGeometry(List<AisMessage> raw) {
        try {
            return AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults()).accepted();
        } catch (RuntimeException e) {
            log.debug("建线清洗失败，沿用原始窗口：{}", e.getMessage());
            return raw;
        }
    }

    /** 相邻间距 ≥ {@value #MIN_TRACK_GAP_M} m 才收为顶点（零长段无方向，无法建折线）。 */
    private static List<WaterwayGeometry.Vertex> toVertices(List<AisMessage> observations) {
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>();
        for (AisMessage observation : observations) {
            WaterwayGeometry.Vertex vertex = new WaterwayGeometry.Vertex(observation.lon(), observation.lat());
            if (vertices.isEmpty() || planarDistanceM(vertices.get(vertices.size() - 1), vertex) >= MIN_TRACK_GAP_M) {
                vertices.add(vertex);
            }
        }
        return vertices;
    }

    /** 沿线尾走向延伸一段（见类注释「参考线怎么来」）。 */
    private static WaterwayGeometry.Vertex offsetM(WaterwayGeometry.Vertex from, double bearingDeg, double distanceM) {
        double rad = Math.toRadians(bearingDeg);
        double east = distanceM * Math.sin(rad);
        double north = distanceM * Math.cos(rad);
        double metersPerDegLon = WaterwayGeometry.METERS_PER_DEG_LAT * Math.cos(Math.toRadians(from.lat()));
        return new WaterwayGeometry.Vertex(
                from.lon() + east / metersPerDegLon,
                from.lat() + north / WaterwayGeometry.METERS_PER_DEG_LAT);
    }

    /** 参考线延伸长度：覆盖整个预测时域的最快航速行程。 */
    private double extensionM() {
        return settings.horizonSec() * params.vMaxCraftMs() + EXTENSION_MARGIN_M;
    }

    private MultiStepPredictor.Params predictorParams() {
        return MultiStepPredictor.Params.fromPredictionParams(
                settings.toParams(), params.laneHalfWidthM(), params.lTolM(), params.wTolM());
    }

    /**
     * 把沿航道速度偏置加进参与沿程推进的速度（77 §8 G1，{@code predAlongSpeedBiasMs}）。
     *
     * <h3>为什么必须单独一个方法</h3>
     * <p>偏置的符号、生效条件、反向护栏三条都容易写错，而它们写错时<b>系统不会报错</b>——
     * 只是预测越校越偏，且表现为「数值慢慢漂」而非崩溃。集中在一处并配单测，
     * 是让这类错误有地方被抓到。
     *
     * <h3>符号（按 80 §3.2.10 推一遍，不要凭直觉）</h3>
     * <p>校准器给 {@code bias = μ_along / τ̄}，{@code μ_along = s_est − s_pred}：
     * {@code μ_along > 0} 表示<b>实际比预测更靠 s 增大的一侧</b>。{@code s} 是沿参考线的
     * <b>绝对弧长</b>（上行为负速度、下行为正速度，同一个坐标系），因此偏置直接<b>代数相加</b>：
     * 上行船「实际更靠前」意味着 {@code s} 更小、{@code μ_along < 0}、偏置为负，加到负速度上
     * 使速度更负、推进量更大。<b>不能</b>乘 {@code sign(v_s)}——那恰好把上行船的偏置推反。
     *
     * <h3>两条护栏</h3>
     * <ul>
     *   <li><b>静止船不加</b>（{@code |v| ≤ }{@value #ALONG_BIAS_MIN_SPEED_MS} m/s）：
     *       偏置修正的是里程系统性偏差，对停泊船无意义。</li>
     *   <li><b>不得反向</b>：{@code |bias|} 上限 0.5 m/s，若船本身只有 0.2 m/s 且偏置为
     *       −0.5，直加会把 {@code s} 拽回去，破坏下游「上行船 s 单调」的判据。撞到反向就停在
     *       0。这条不只是保守：船速与里程偏差同量级时，偏置本身已不可辨识。</li>
     * </ul>
     *
     * <p>偏置只改<b>位置</b>推进量，不改协方差：它是确定性的系统偏差修正，
     * 不确定度仍由 {@code P_ss} 与 {@code α·τ} 承担。
     *
     * @param vAlongMs 滤波器估计的带号纵向速度，m/s
     * @return 参与推进的速度，m/s
     */
    double alongSpeedForPrediction(double vAlongMs) {
        double bias = alongSpeedBiasMs.getAsDouble();
        if (!Double.isFinite(bias) || bias == 0.0) {
            return vAlongMs;
        }
        if (!(Math.abs(vAlongMs) > ALONG_BIAS_MIN_SPEED_MS)) {
            return vAlongMs;
        }
        double biased = vAlongMs + bias;
        return vAlongMs * biased <= 0.0 ? 0.0 : biased;
    }

    private LateralOuFilter.Params lateralParams() {
        LateralOuFilter.Params base = LateralOuFilter.Params.defaults();
        return new LateralOuFilter.Params(
                params.tauESec(),
                settings.sigma0CrossM(),
                settings.sigma0AlongM(),
                base.eMeanWindowSec(),
                base.deDtWindowSec(),
                base.lcStepM(),
                base.lcCount(),
                base.lcInflate(),
                base.tauLcSec(),
                base.lcModelEnabled(),
                params.laneHalfWidthM());
    }

    /**
     * 一版预测的计算结果：对外契约投影 ＋ 内部点列。
     *
     * @param outcome 对外产出（点列已投影成 {@link EstimatedPoint}）
     * @param samples 内部点列（带曲线坐标、σ、切向），撒探针时要整份留档
     */
    private record Computed(Outcome outcome, List<MultiStepPredictor.Sample> samples) {
    }

    private Computed predictFromState(ShipState state, long t0Ms, String mmsi) {
        MultiStepPredictor.Params predictParams = predictorParams();

        MultiStepPredictor.Input input = new MultiStepPredictor.Input(
                t0Ms,
                state.along.s(),
                alongSpeedForPrediction(state.along.vAlongMs()),
                state.along.varianceS(),
                state.along.covarianceSV(),
                state.along.varianceV(),
                MultiStepPredictor.LateralForecast.from(state.lateral, state.lateral.varianceE(),
                        settings.sigma0CrossM(), params.tauESec()),
                state.geometry,
                speedLimit);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, predictParams);
        long validitySec = MultiStepPredictor.validitySec(samples, predictParams);
        List<EstimatedPoint> points = samples.stream()
                .map(MultiStepPredictor.Sample::toEstimatedPoint)
                .toList();

        Outcome outcome = new Outcome(
                mmsi,
                t0Ms,
                samples.get(0).courseDeg(),
                validitySec,
                state.premiseStatus,
                points,
                new ArrayList<>(state.zSeries));
        return new Computed(outcome, samples);
    }

    // ───────────── 内部：小工具 ─────────────

    private static void validate(AisMessage observation) {
        if (observation == null) {
            throw new IllegalArgumentException("观测不得为 null");
        }
        if (observation.mmsi() == null || observation.mmsi().isBlank()) {
            throw new IllegalArgumentException("mmsi 不得为空");
        }
        if (!Double.isFinite(observation.lon()) || observation.lon() < -180.0 || observation.lon() > 180.0) {
            throw new IllegalArgumentException("经度超出范围：" + observation.lon());
        }
        if (!Double.isFinite(observation.lat()) || observation.lat() < -90.0 || observation.lat() > 90.0) {
            throw new IllegalArgumentException("纬度超出范围：" + observation.lat());
        }
        if (!Double.isFinite(observation.sog()) || observation.sog() < 0.0) {
            throw new IllegalArgumentException("对地航速不得为负或非有限值：" + observation.sog());
        }
        // MED-BUG.43：AIS 的「不可用」是<b>有具体取值</b>的，不是 NaN。上游按十进制换算后，
        // 1023（0.1 kn）会变成一个看着正常的 102.3 kn、3600（0.1°）会变成 360.0°，
        // 511（整度航向不可用）也同为哨兵。不拦的后果不是"这一帧略偏"，而是：
        // 102.3 kn ⇒ 1 s 位移 52 m，速度状态被一帧打歪，此后几十拍 s 都偏；
        // 360.0°/511° ⇒ 参考线走向被指到错误方位，"船不动了"或"船横着走"两种静默错误。
        // 判据放在 validate 里而不是 buildGeometry 里：哨兵是<b>观测本身不可用</b>，
        // 与"这一版建不出线"是两件事，前者该丢帧，后者该等下一拍。
        if (observation.sog() >= AIS_SOG_UNAVAILABLE_KN) {
            throw new IllegalArgumentException(
                    "对地航速为 AIS 不可用哨兵值（≥" + AIS_SOG_UNAVAILABLE_KN + " kn）："
                            + observation.sog());
        }
        if (!Double.isFinite(observation.cog())) {
            throw new IllegalArgumentException("对地航向必须为有限值：" + observation.cog());
        }
        if (observation.cog() < 0.0 || observation.cog() >= AIS_COG_UNAVAILABLE_DEG) {
            throw new IllegalArgumentException(
                    "对地航向超出 [0,360)（含 AIS 不可用哨兵 360.0／511）：" + observation.cog());
        }
    }

    private static double planarDistanceM(WaterwayGeometry.Vertex from, WaterwayGeometry.Vertex to) {
        double latRef = (from.lat() + to.lat()) / 2.0;
        double metersPerDegLon = WaterwayGeometry.METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latRef));
        double east = (to.lon() - from.lon()) * metersPerDegLon;
        double north = (to.lat() - from.lat()) * WaterwayGeometry.METERS_PER_DEG_LAT;
        return Math.hypot(east, north);
    }
}
