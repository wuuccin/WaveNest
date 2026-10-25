package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.AisObservationFilter;
import com.waterlogistics.algo.estimation.AlongTrackKalmanFilter;
import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.LateralOuFilter;
import com.waterlogistics.algo.estimation.MultiStepPredictor;
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
 *   <li><b>前提监测 {@code PremiseMonitor} 未接</b>：其 P 判据需要窗口与阈值，两项在
 *       {@code sys_param} 中均未登记（不臆造键名），故前提一律给 {@code VALID}，
 *       与匀速版口径一致，待登记后接。</li>
 *   <li><b>航段限速</b>：无中心线台账即无分段限速，故用 {@link MultiStepPredictor.SpeedLimitLookup#unbounded()}；
 *       车速仍受滤波器自己的 {@code vMaxCraftMs} 限幅。</li>
 * </ul>
 *
 * <p><b>本类不感知 Spring、不写库</b>：装配见 {@link PredictionConfig}，调用见 {@link PredictionService}。
 */
public class PrecisePredictionEngine {

    private static final Logger log = LoggerFactory.getLogger(PrecisePredictionEngine.class);

    /** 快照 {@code zSeries} 保留的新息条数（E 组累积判据窗长 32，够用且不膨胀快照）。 */
    static final int Z_SERIES_CAP = 32;

    /** 建参考线时相邻顶点的最小间距，m：折线零长段无方向，必须去重。 */
    static final double MIN_TRACK_GAP_M = 5.0;

    /** 线尾余量，m：投影逼近折线末端时重建参考线。 */
    static final double TAIL_MARGIN_M = 100.0;

    /** 参考线延伸长度在时域覆盖之外的额外余量，m。 */
    private static final double EXTENSION_MARGIN_M = 500.0;

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

        boolean initialized;

        long lastAppliedTsMs = Long.MIN_VALUE;

        double predictedVarianceS;

        double normalizedInnovation;
    }

    private final PredictionSettings settings;

    private final Params params;

    private final Map<String, ShipState> states = new ConcurrentHashMap<>();

    public PrecisePredictionEngine(PredictionSettings settings, Params params) {
        if (settings == null || params == null) {
            throw new IllegalArgumentException("settings / params 不得为 null");
        }
        this.settings = settings;
        this.params = params;
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
                return Optional.of(predictFromState(state, state.lastAppliedTsMs, observation.mmsi()));
            }

            if (state.geometry == null) {
                WaterwayGeometry geometry = buildGeometry(state.window);
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
            }
            // 时标相同或更旧的观测：不推进状态，直接按当前状态出预测（F3 的重算就走这条）

            if (!state.initialized) {
                return Optional.empty();
            }

            if (state.along.s() >= state.geometry.totalLengthM() - TAIL_MARGIN_M) {
                log.warn("参考线将尽，重建并重锚：mmsi={} s={} 总长={}",
                        observation.mmsi(), state.along.s(), state.geometry.totalLengthM());
                WaterwayGeometry rebuilt = buildGeometry(state.window);
                if (rebuilt != null) {
                    state.geometry = rebuilt;
                    state.initialized = false;
                    state.lastAppliedTsMs = Long.MIN_VALUE;
                    for (AisMessage historic : state.window) {
                        applyObservation(state, historic);
                    }
                }
            }

            return Optional.of(predictFromState(state, observation.ts(), observation.mmsi()));
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

        double nis = state.along.normalizedInnovation();
        if (Double.isFinite(nis)) {
            state.normalizedInnovation = nis;
            if (state.zSeries.size() >= Z_SERIES_CAP) {
                state.zSeries.removeFirst();
            }
            state.zSeries.addLast(nis);
        }
    }

    // ───────────── 内部：参考线与预测 ─────────────

    /**
     * 由实测窗口建参考折线（退化 1）。
     *
     * <p>先用 {@link AisObservationFilter} 清洗<b>建线用的点</b>；清洗后不足两点则退回原始窗口
     * （清洗只影响参考线质量，不得让整条链路停摆——滤波链本身不因单条被清洗点停拍）。
     *
     * @return 参考折线；实测点不足两点时为空
     */
    private WaterwayGeometry buildGeometry(Deque<AisMessage> window) {
        List<AisMessage> raw = new ArrayList<>(window);
        List<AisMessage> cleaned = cleanForGeometry(raw);

        List<WaterwayGeometry.Vertex> vertices = toVertices(cleaned);
        if (vertices.size() < 2) {
            vertices = toVertices(raw);
        }
        if (vertices.size() < 2) {
            return null;
        }

        WaterwayGeometry.Vertex last = vertices.get(vertices.size() - 1);
        WaterwayGeometry.Vertex previous = vertices.get(vertices.size() - 2);
        double bearingDeg = ConstantVelocityPredictor.bearingDeg(
                previous.lon(), previous.lat(), last.lon(), last.lat());
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

    private Outcome predictFromState(ShipState state, long t0Ms, String mmsi) {
        MultiStepPredictor.Params predictParams = predictorParams();

        MultiStepPredictor.Input input = new MultiStepPredictor.Input(
                t0Ms,
                state.along.s(),
                state.along.vAlongMs(),
                state.along.varianceS(),
                state.along.covarianceSV(),
                state.along.varianceV(),
                MultiStepPredictor.LateralForecast.from(state.lateral, state.lateral.varianceE(),
                        settings.sigma0CrossM(), params.tauESec()),
                state.geometry,
                MultiStepPredictor.SpeedLimitLookup.unbounded());

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, predictParams);
        long validitySec = MultiStepPredictor.validitySec(samples, predictParams);
        List<EstimatedPoint> points = samples.stream()
                .map(MultiStepPredictor.Sample::toEstimatedPoint)
                .toList();

        return new Outcome(
                mmsi,
                t0Ms,
                samples.get(0).courseDeg(),
                validitySec,
                PremiseStatus.VALID,
                points,
                new ArrayList<>(state.zSeries));
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
        if (!Double.isFinite(observation.cog())) {
            throw new IllegalArgumentException("对地航向必须为有限值：" + observation.cog());
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
