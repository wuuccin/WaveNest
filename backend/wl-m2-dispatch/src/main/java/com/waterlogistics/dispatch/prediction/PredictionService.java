package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.PredictionResult;
import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.algo.inflight.IntentChangeJudge;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 船舶状态估计与轨迹预测服务（M2 · 卢肄文 · 模块二功能 2）——预测刷新驱动链的<b>中段</b>。
 *
 * <p><b>一套实现两种触发</b>（《20-系统架构》§2 硬约束 3）：本服务同时服务「常驻滚动预测」
 * 与「接单精算预测」，两者共用同一算法实现，本类只做编排
 * （《包结构设计》§二 铁律 5：<b>禁止两套代码</b>）。
 *
 * <p><b>驱动链（《10-16 当日任务速览》§3.3）</b>：
 * <pre>
 *   新状态(data.ais) → 本类 refresh → ① 快照更新（供 M3 端口取用）
 *                                   → ② 航向跳变超阈 ⇒ 发重算请求（解锁 6a）
 *                                   → ③ 配成 HeadingSample（供 E 组 6b 判据）
 * </pre>
 *
 * <h3>两种实现与开关（10-23 F4「预测切精算」）</h3>
 * <p>算法实现由 {@link PredictionProfile} 选择，属性 {@code wavenest.m2.prediction.mode} 取值
 * {@code precise}（缺省，精算版）或 {@code constant}（匀速版）。<b>回退只需改这一行并重启</b>：
 * <pre>
 *   wavenest.m2.prediction.mode: constant
 * </pre>
 * 两条路径共用本类签名、共用同一份快照与事件，故回退不影响下游任何一句代码。
 *
 * <p><b>先补网再切换</b>：所有输出都过 {@link PredictionAssurance}——精算版不过网则<b>本帧</b>
 * 回落匀速版并计一次（{@link #preciseRejectionCount()}），匀速版不过结构断言记 error 仍照发
 * （它是最后兜底，停发会让该船从画面上消失）。理由与分级口径见该类注释。
 *
 * <p>状态存于进程内 {@link ConcurrentHashMap}（每船一份最新快照 ＋ 单调递增版本号）。
 * 分水岭：<b>快照本身不落库</b>（Redis／持久化由 M6 承接）；但调度结果要落
 * {@code task_log}（见 {@code dispatch.record.TaskLogRecorder}）。
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    /**
     * 触发重算请求的<b>航向跳变阈值</b>（度）。
     *
     * <p>物理含义与 E 组 6b 判据的 Θ_th 同源（都判「船是否改成向」），故直接引用其公开常量，
     * 避免代码里出现两个各自为政的「20°」。{@code sys_param} 目前无此键
     * （E 组 26 项亦无 camelCase 键名），按纪律<b>不臆造键名</b>；待《37》§5.14.1 登记后改读表。
     */
    public static final double RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG =
            IntentChangeJudge.SUGGESTED_THETA_TH_DEG;

    /** 触发原因文案（追溯用，进 {@link RecomputeRequest}）。 */
    static final String REASON_COURSE_JUMP = "预测主轴方位跳变超阈（简化版变向判据）";

    private final PredictionSettings settings;

    private final RecomputeRequester recomputeRequester;

    private final ApplicationEventPublisher eventPublisher;

    /** 实现选择（模式 ＋ 断言网 ＋ 精算引擎）；由 {@link PredictionConfig} 装配。 */
    private final PredictionProfile profile;

    /** 「现在」的取时口，测试可注入固定值；生产为 {@link System#currentTimeMillis()}。 */
    private final LongSupplier clock;

    /** 每船最新快照。key = mmsi。 */
    private final Map<String, PredictionSnapshot> latestSnapshots = new ConcurrentHashMap<>();

    /**
     * 每船<b>最近一条观测</b>（不落库、不进快照），供重算时复用。
     *
     * <p>重算信号只带 {@code mmsi}＋方位差，不带观测；没有本表，重算就无从下手。
     */
    private final Map<String, AisMessage> lastObservations = new ConcurrentHashMap<>();

    /**
     * 每船上一版观测的<b>对地航向</b>（度），供航向跳变判定。
     *
     * <p>为什么用观测而不是快照里的 {@code courseDeg}：精算版快照的主轴方位取的是<b>参考线走向</b>
     * （曲线坐标的 {@code course(s)}），它本来就不随船转弯而变；拿它做「船是否改成向」的判据会
     * 永远判不出跳变。两种实现统一在「观测维度」比较，语义才一致。
     */
    private final Map<String, Double> lastObservedCog = new ConcurrentHashMap<>();

    /**
     * 每船版本号计数器。
     *
     * <p>契约把 {@code version} 定为 {@code long} 且「判新旧用版本字段、不靠时间戳推测」，
     * 故这里用<b>单调递增计数器</b>而非哈希串：天然可比较、无碰撞。
     */
    private final Map<String, AtomicLong> versionCounters = new ConcurrentHashMap<>();

    /** 精算输出被断言网拒绝、回落匀速版的累计次数。 */
    private final AtomicLong preciseRejections = new AtomicLong();

    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester) {
        this(settings, recomputeRequester, event -> { }, PredictionProfile.constant(), System::currentTimeMillis);
    }

    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher) {
        this(settings, recomputeRequester, eventPublisher, PredictionProfile.constant(),
                System::currentTimeMillis);
    }

    @Autowired
    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher, PredictionProfile profile) {
        this(settings, recomputeRequester, eventPublisher, profile, System::currentTimeMillis);
    }

    /** 全参构造（{@code clock} 供单测注入固定「现在」）。 */
    PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher, PredictionProfile profile, LongSupplier clock) {
        this.settings = settings;
        this.recomputeRequester = recomputeRequester;
        this.eventPublisher = eventPublisher;
        this.profile = profile == null ? PredictionProfile.constant() : profile;
        this.clock = clock;
    }

    /** 当前生效的预测实现。 */
    public PredictionMode mode() {
        return profile.mode();
    }

    /** 精算输出被断言网拒绝、回落匀速版的累计次数（数值核验用）。 */
    public long preciseRejectionCount() {
        return preciseRejections.get();
    }

    /**
     * 收到一条船舶观测后刷新该船预测快照——<b>常驻层与接单精算共用的唯一入口</b>。
     *
     * <p>刷新成功后顺带判定「预测前提是否已实质改变」：与上一版观测对地航向比较，
     * 变化达到 {@link #RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG} 即发一次重算请求
     * （首帧不算跳变，没有可比的上一版）。
     *
     * @param message 解析后的 AIS 船位（{@code sog} 为<b>节</b>、{@code cog} 为度，见契约 javadoc）
     * @return 刷新后的快照（版本号已 +1）
     * @throws IllegalArgumentException 观测越界时抛出（由调用方决定丢弃或告警；
     *         抛出时<b>不写快照、不递增版本</b>，本条观测视为未发生）
     */
    public PredictionSnapshot refresh(AisMessage message) {
        return computeSnapshot(message, true);
    }

    /**
     * 响应一次重算请求：用该船<b>最新一条观测</b>重算一版快照（版本号 +1，广播更新事件）。
     *
     * <p><b>这是 F3「重算链接通」的落地点</b>（10-23 任务清单 §三 卢肄文段）：重算请求器收到
     * 信号后不再只打日志，而是回到本类真算一版，让下游（M3 端口、大屏 {@code ship_traj}、
     * E 组判据）确实看到新版本，闭环前一半才算接上。
     *
     * <p><b>为什么不再回发重算信号</b>：本次重算用的就是触发它的那条观测，观测航向必然与
     * 当前版一致（方位差为 0），若仍走信号判定，就只是把「同一件事」再判一遍；显式关掉
     * 回发，是为了让链路无自激可能，而不是依赖「算出来一定相等」这个巧合。
     *
     * @param mmsi 目标船标识
     * @return 重算后的快照；该船从无观测时为空（调用方无需处理）
     */
    public Optional<PredictionSnapshot> recompute(String mmsi) {
        AisMessage observation = lastObservations.get(mmsi);
        if (observation == null) {
            return Optional.empty();
        }
        return Optional.of(computeSnapshot(observation, false));
    }

    /**
     * 算一版快照并登记（refresh 与 recompute 的唯一共干）。
     *
     * @param signalOnPremiseChange 是否顺带判定「预测前提已实质改变」并回发重算信号；
     *                             只有入端 {@link #refresh} 为 {@code true}
     */
    private PredictionSnapshot computeSnapshot(AisMessage message, boolean signalOnPremiseChange) {
        long version = nextVersion(message.mmsi());
        PredictionSnapshot snapshot = buildSnapshot(message, version);

        PredictionSnapshot previous = latestSnapshots.put(message.mmsi(), snapshot);
        lastObservations.put(message.mmsi(), message);
        Double previousCog = lastObservedCog.put(message.mmsi(), message.cog());

        log.debug("预测刷新：mmsi={} version={} points={} 有效期={}s 前提={} 模式={}",
                snapshot.mmsi(), snapshot.version(), snapshot.points().size(),
                snapshot.validitySec(), snapshot.premise(), profile.mode().code());

        // 先广播本版，再判前提：重算若被触发会产出更高版本，顺序反了会让下游先收到旧版
        eventPublisher.publishEvent(new PredictionUpdatedEvent(snapshot.toFrame()));
        if (signalOnPremiseChange) {
            maybeRequestRecompute(previousCog, message, snapshot);
        }
        return snapshot;
    }

    /**
     * 按当前模式算一版快照（含断言网与回落）。
     *
     * <p>精算不可用（冷启动／观测时标不前进）或输出不过网时，<b>本帧</b>回落匀速版：
     * 宁可少一帧精算，也不把没验过的数发出去，更不让该船整帧缺失。
     */
    private PredictionSnapshot buildSnapshot(AisMessage message, long version) {
        if (profile.mode() == PredictionMode.PRECISE && profile.engine() != null) {
            Optional<PrecisePredictionEngine.Outcome> computed = profile.engine().predict(message);
            if (computed.isPresent()) {
                PrecisePredictionEngine.Outcome outcome = computed.get();
                Optional<PredictionAssurance.Violation> violation = profile.assurance().check(
                        outcome.t0Ms(), clock.getAsLong(), outcome.validitySec(), outcome.points());
                if (violation.isEmpty()) {
                    return preciseSnapshot(version, outcome);
                }
                long rejections = preciseRejections.incrementAndGet();
                log.warn("精算输出未过区间断言，本帧回落匀速版：mmsi={} 断言={} 细节={}（累计 {} 次）",
                        message.mmsi(), violation.get().code().code(), violation.get().detail(), rejections);
            } else {
                log.debug("精算暂不可用（冷启动或观测时标未前进），本帧回落匀速版：mmsi={}",
                        message.mmsi());
            }
        }
        return constantSnapshot(version, message);
    }

    /** 匀速版（回退路径）：二维匀速外推 ＋ 固定噪声带。 */
    private PredictionSnapshot constantSnapshot(long version, AisMessage message) {
        // 单位归一：AisMessage 保持源单位（节），算法层统一 SI（m/s）
        double sogMs = ConstantVelocityPredictor.knotsToMs(message.sog());

        PredictionResult result = ConstantVelocityPredictor.predict(
                message.mmsi(),
                message.lon(),
                message.lat(),
                sogMs,
                message.cog(),
                message.ts(),
                settings.toParams());

        PredictionSnapshot snapshot = new PredictionSnapshot(
                result.mmsi(),
                version,
                result.t0Ms(),
                result.courseDeg(),
                result.validitySec(),
                result.premise().code(),
                result.points().stream().map(PredictionService::toFramePoint).toList(),
                // 以下五项属精算版：匀速路径一律占位（zSeries 为 null，不是空集合，
                // 以便下游区分「无新息」与「有新息但为空」）
                null, null, null, null, null);

        assertStructure(snapshot);
        return snapshot;
    }

    /** 精算版：点列、主轴方位、有效期、前提与新息序列都来自引擎，不再用配置常量占位。 */
    private PredictionSnapshot preciseSnapshot(long version, PrecisePredictionEngine.Outcome outcome) {
        PredictionSnapshot snapshot = new PredictionSnapshot(
                outcome.mmsi(),
                version,
                outcome.t0Ms(),
                outcome.courseDeg(),
                outcome.validitySec(),
                outcome.premise().code(),
                outcome.points().stream().map(PredictionService::toFramePoint).toList(),
                outcome.zSeries(),
                // 偏差校准与标定因子需「预测—实测配对样本表」（A-11，M6 侧未建），先留空
                null, null, null, null);

        assertStructure(snapshot);
        return snapshot;
    }

    /**
     * 匀速版的结构断言（精算版已由 {@link PredictionAssurance#check} 判过，含地理框）。
     *
     * <p>违反即实现缺陷：记 error 仍照发——匀速版是最后兜底，停发会让该船从画面上消失，
     * 比发一条带已知缺陷的轨迹更糟。
     */
    private void assertStructure(PredictionSnapshot snapshot) {
        Optional<PredictionAssurance.Violation> violation = profile.assurance().checkStructure(
                snapshot.t0Ms(), clock.getAsLong(), snapshot.validitySec(),
                snapshot.points().stream()
                        .map(point -> new EstimatedPoint(point.t(), point.lon(), point.lat(),
                                point.sigmaAlong(), point.sigmaCross()))
                        .toList());
        violation.ifPresent(found -> log.error("匀速版输出违反结构断言（实现缺陷）：mmsi={} 断言={} 细节={}",
                snapshot.mmsi(), found.code().code(), found.detail()));
    }

    /** 取某船最新快照（无则空）。M3 端口与本模块消费方都走这里。 */
    public Optional<PredictionSnapshot> snapshotOf(String mmsi) {
        return Optional.ofNullable(latestSnapshots.get(mmsi));
    }

    /** 取全部在跟踪船舶的最新快照，按 MMSI 排序，便于稳定展示与联调比对。 */
    public List<PredictionSnapshot> latestSnapshots() {
        return latestSnapshots.values().stream()
                .sorted(Comparator.comparing(PredictionSnapshot::mmsi))
                .toList();
    }

    /** 当前在跟踪的船数。 */
    public int trackedShipCount() {
        return latestSnapshots.size();
    }

    /**
     * 把一条<b>实测</b> AIS 观测配成 E 组 6b 判据所需的样本（解锁吴可欣的闭环枢纽）。
     *
     * <p>{@code HeadingSample} 要的四个量中，{@code predictedTangentDeg}（预测航迹切向）
     * 只能由本模块给出——{@code algo.inflight.HeadingSample} 的 javadoc 明写
     * 「预测切向最终来自 A 组 {@code PredictionSnapshot}，且必须经 M2 常驻层取用完整对象，
     * <b>不得从 WS 帧 {@code ship_traj} 反推</b>」。本方法即那个「常驻层取用」的唯一出口。
     *
     * @param measured 实测船位（同时被 {@link #refresh} 刷新过快照时配对才有意义）
     * @return 配对样本；该船尚无快照时为空（调用方跳过本拍即可）
     */
    public Optional<HeadingSample> toHeadingSample(AisMessage measured) {
        return snapshotOf(measured.mmsi())
                .map(snapshot -> new HeadingSample(
                        measured.cog(),
                        tangentDegAt(snapshot, measured.ts()),
                        ConstantVelocityPredictor.knotsToMs(measured.sog()),
                        measured.ts()));
    }

    /** 快照在指定时刻的航迹切向（度，正北 0、顺时针）。 */
    static double tangentDegAt(PredictionSnapshot snapshot, long tsMs) {
        List<ShipTrajPoint> points = snapshot.points();
        if (points.isEmpty()) {
            return snapshot.courseDeg();
        }

        int nearest = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long distance = Math.abs(points.get(i).t() - tsMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }

        if (nearest + 1 < points.size()) {
            ShipTrajPoint from = points.get(nearest);
            ShipTrajPoint to = points.get(nearest + 1);
            return ConstantVelocityPredictor.bearingDeg(from.lon(), from.lat(), to.lon(), to.lat());
        }
        if (nearest > 0) {
            ShipTrajPoint from = points.get(nearest - 1);
            ShipTrajPoint to = points.get(nearest);
            return ConstantVelocityPredictor.bearingDeg(from.lon(), from.lat(), to.lon(), to.lat());
        }
        return snapshot.courseDeg();
    }

    /**
     * 两角之差取最短弧，返回 {@code [0, 180]} 度。
     *
     * <p>必须取最短弧：航向 350° → 10° 实际只转了 20°，直接相减会得到 340° 的假跳变。
     */
    static double shortestArcDeg(double fromDeg, double toDeg) {
        double raw = Math.abs(
                ConstantVelocityPredictor.normalizeDeg(toDeg)
                        - ConstantVelocityPredictor.normalizeDeg(fromDeg));
        raw = raw % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }

    private void maybeRequestRecompute(Double previousCogDeg, AisMessage message,
            PredictionSnapshot current) {
        if (previousCogDeg == null) {
            // 首帧：没有可比的上一版，不算跳变，避免刚上线就刷一串重算请求
            return;
        }

        double deltaDeg = shortestArcDeg(previousCogDeg, message.cog());
        if (deltaDeg < RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG) {
            return;
        }

        recomputeRequester.requestRecompute(new RecomputeRequest(
                current.mmsi(),
                current.version(),
                previousCogDeg,
                ConstantVelocityPredictor.normalizeDeg(message.cog()),
                deltaDeg,
                REASON_COURSE_JUMP,
                current.t0Ms()));
    }

    private long nextVersion(String mmsi) {
        return versionCounters.computeIfAbsent(mmsi, key -> new AtomicLong()).incrementAndGet();
    }

    /** 算法层输出点 → WS 帧点：字段语义一致（σ 均为标准差，单位 m）。 */
    private static ShipTrajPoint toFramePoint(EstimatedPoint point) {
        return new ShipTrajPoint(
                point.tMs(), point.lon(), point.lat(), point.sigmaAlongM(), point.sigmaCrossM());
    }
}
