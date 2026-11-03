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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <h3>11-02 回流校准接入（阶段二 5）</h3>
 * <p>每拍在算快照前顺带问一次 {@link PredictionCalibrationService}「这船现在可下发的标定因子是多少」，
 * 结果写进快照的 {@code calibrationFactor}／{@code deviationM}。该服务自带节流（默认 60 s／船），
 * 故不会把 IO 压到每拍；<b>它不可用（样本不足 200 或无可信区间）时字段为 {@code null}</b>，
 * 而不是 1.0——「未标定」和「标定过且中性」是两件事，下游必须能区分。
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

    /**
     * 惰性清扫的触发间隔：每 N 次 {@code refresh} 顺带做一次 {@link #evictStale(long)}。
     *
     * <p>为什么不单起一个定时线程：本服务的每船状态是纯内存 Map，清扫是 O(船数) 的轻操作，
     * 搭在已有心跳上最省；而多一个 {@code @Scheduled} 就多一处「生命周期不由本类掌握」的时序问题
     * （测试里要造 Scheduler、关闭时要等它退出）。1000 次 1 Hz 输入约 17 分钟一次，
     * 泄漏窗口远小于任何有意义的量级。
     */
    static final int EVICT_EVERY_N_REFRESHES = 1000;

    private final PredictionSettings settings;

    private final RecomputeRequester recomputeRequester;

    private final ApplicationEventPublisher eventPublisher;

    /** 实现选择（模式 ＋ 断言网 ＋ 精算引擎）；由 {@link PredictionConfig} 装配。 */
    private final PredictionProfile profile;

    /** 「现在」的取时口，测试可注入固定值；生产为 {@link System#currentTimeMillis()}。 */
    private final LongSupplier clock;

    /**
     * 回流校准入口；测试构造或未装配时为 {@code null}（快照的校准字段一律 {@code null}）。
     *
     * <p>刻意设计成<b>可空</b>：校准是「有则更好」的增强，不是预测成立的前提。
     * 把它做成必需依赖，会让所有既有单测都要造一台校准服务＋库，而它们验的是预测本身。
     *
     * <p>{@code volatile}：由 {@link #bindCalibration} 在装配期写入、运行期只读，
     * 但装配与首个 AIS 到达之间没有 happens-before 保证（MQ 监听线程可能先跑起来），
     * 加 volatile 让「已装配」这件事对其他线程可见，避免读到一个陈旧的 null。
     */
    private volatile PredictionCalibrationService calibration;

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

    /**
     * 每船计算锁（HIGH.23）：串行化「取版本 → 建快照 → 登记 → 发布」。
     *
     * <p><b>为什么按船串行</b>：版本号取自 {@link AtomicLong}，但「取版本」与「登记快照」
     * 是两步——无同步时两条线程可能交错成「A 取 v1 → B 取 v2 → B 先 put → A 后 put」，
     * <b>低版本覆盖高版本</b>，下游按版本判新旧的口径就会永远停在旧版。
     * refresh 由入端按船 1 Hz 调用、recompute 是偶发，同一船的锁竞争可忽略；
     * 建快照放在锁内换的是「同船两帧绝不乱序」，吞吐量不受影响（不同船并行如常）。
     *
     * <p>清扫时<b>不回收本表</b>：锁对象只有 16 字节，且回收它需要保证「没有线程正持有／正等待」，
     * 而这在无锁结构里做不到——把锁删掉后另一个线程 {@code computeIfAbsent} 会拿到一把新锁，
     * 于是同一船出现两把锁，HIGH.23 的串行化保证当场失效。宁留一点内存，不破并发正确性。
     */
    private final Map<String, Object> computeLocks = new ConcurrentHashMap<>();

    /** 精算输出被断言网拒绝、回落匀速版的累计次数。 */
    private final AtomicLong preciseRejections = new AtomicLong();

    /** 惰性清扫的调用计数（每 {@value #EVICT_EVERY_N_REFRESHES} 次触发一次）。 */
    private final AtomicLong refreshCount = new AtomicLong();

    /**
     * 「配置为精算但引擎为 null」的告警只打一次（LOW.19）。
     *
     * <p>用 {@link AtomicBoolean} 而不是每次都 warn：这是<b>启动期装配缺陷</b>，
     * 它会持续存在于每一拍；逐拍告警会在几秒内刷满日志，反而盖住真正的运行期异常。
     */
    private final AtomicBoolean preciseEngineMissingWarned = new AtomicBoolean();

    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester) {
        this(settings, recomputeRequester, event -> { }, PredictionProfile.constant(),
                System::currentTimeMillis, null);
    }

    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher) {
        this(settings, recomputeRequester, eventPublisher, PredictionProfile.constant(),
                System::currentTimeMillis, null);
    }

    /**
     * 生产装配：四个必需依赖。
     *
     * <p>校准服务<b>不在此处</b>，走 {@link #bindCalibration} 的 setter 注入。理由不是循环依赖，
     * 而是构造器位次：本类有多个构造器供单测挑最短的一条（{@code clock} 用固定值），
     * 把校准硬塞进构造器会让「5 参 ＋ {@link LongSupplier}」与「5 参 ＋ 校准服务」形成
     * 两个 5 参重载，而 lambda 实参在重载解析里无法被正确排除，既有测试全部编不过。
     * setter 注入把这个可选增强从构造签名里摘出去，测试与生产各自的形态都干净。
     */
    @Autowired
    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher, PredictionProfile profile) {
        this(settings, recomputeRequester, eventPublisher, profile, System::currentTimeMillis,
                null);
    }

    /** 全参构造（{@code clock} 供单测注入固定「现在」）。 */
    PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher, PredictionProfile profile, LongSupplier clock) {
        this(settings, recomputeRequester, eventPublisher, profile, clock, null);
    }

    /** 全参构造（{@code clock} 供单测注入固定「现在」，{@code calibration} 可空）。 */
    PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher, PredictionProfile profile, LongSupplier clock,
            PredictionCalibrationService calibration) {
        this.settings = settings;
        this.recomputeRequester = recomputeRequester;
        this.eventPublisher = eventPublisher;
        this.profile = profile == null ? PredictionProfile.constant() : profile;
        this.clock = clock;
        this.calibration = calibration;
    }

    /**
     * 注入回流校准服务（可选依赖，见 {@link #calibration} 字段说明）。
     *
     * <p>用 setter 而非构造器：{@code PredictionCalibrationService} 的实现需要
     * {@code PredErrorSampleMapper}／{@code SysParamLoader}，本类却会在纯算法单测里被
     * {@code new} 出来；把校准做成必需依赖会让每个验证预测本身的测试都得造一台库。
     */
    @Autowired(required = false)
    void bindCalibration(PredictionCalibrationService calibration) {
        this.calibration = calibration;
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
        PredictionSnapshot snapshot = computeSnapshot(message, true);
        if (refreshCount.incrementAndGet() % EVICT_EVERY_N_REFRESHES == 0L) {
            evictStale(clock.getAsLong());
        }
        return snapshot;
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
     * <p><b>注意本方法仍会 {@code publishEvent}</b>：监听方若在回调里再摸预测，就会构成递归。
     * 该风险的闸门设在 {@link LoggingRecomputeRequester}（同线程深度计数），不在本类——
     * 本类无法约束别人怎么写监听器，而重算信号只有一个入口。
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
     * <p><b>MEDIUM.46：版本号必须可回退</b>。{@code nextVersion} 先自增，随后 {@code buildSnapshot}
     * 可能抛（{@code ConstantVelocityPredictor.predict} 对越界观测抛 IllegalArgumentException）。
     * 若不回退，版本序列出现空洞，而本方法 javadoc 承诺「抛出时不递增版本」。
     *
     * <p>为什么不改成「先校验再取版本」：观测校验逻辑住在算法层（{@code validate}），
     * 在服务层照抄一遍就是《包结构设计》§二 明令禁止的「两套代码」——算法层收紧阈值时
     * 服务层那份不会跟着改，于是又变成"漏校验"。异常路径回退只需一行，且天然与算法层同步。
     * 回退在<b>同船锁内</b>执行，同船请求是串行的，不会出现并发回退踩踏。
     *
     * @param signalOnPremiseChange 是否顺带判定「预测前提已实质改变」并回发重算信号；
     *                             只有入端 {@link #refresh} 为 {@code true}
     */
    private PredictionSnapshot computeSnapshot(AisMessage message, boolean signalOnPremiseChange) {
        Object lock = computeLocks.computeIfAbsent(message.mmsi(), key -> new Object());
        synchronized (lock) {
            PredictionCalibrationService.CalibrationOutcome calibrated = calibrate(message.mmsi());

            long version = nextVersion(message.mmsi());
            PredictionSnapshot snapshot;
            try {
                snapshot = buildSnapshot(message, version, calibrated);
            } catch (RuntimeException e) {
                rollbackVersion(message.mmsi());
                throw e;
            }

            PredictionSnapshot previous = latestSnapshots.put(message.mmsi(), snapshot);
            lastObservations.put(message.mmsi(), message);
            Double previousCog = lastObservedCog.put(message.mmsi(), message.cog());

            log.debug("预测刷新：mmsi={} version={} points={} 有效期={}s 前提={} 模式={} 被替换版本={}",
                    snapshot.mmsi(), snapshot.version(), snapshot.points().size(),
                    snapshot.validitySec(), snapshot.premise(), profile.mode().code(),
                    previous == null ? "无" : previous.version());

            // 先广播本版，再判前提：重算若被触发会产出更高版本，顺序反了会让下游先收到旧版
            publish(snapshot);
            if (signalOnPremiseChange) {
                maybeRequestRecompute(previousCog, message, snapshot);
            }
            return snapshot;
        }
    }

    /** 校准入口：无校准服务或样本不足时返回 {@code null}（快照对应字段留空）。 */
    private PredictionCalibrationService.CalibrationOutcome calibrate(String mmsi) {
        if (calibration == null) {
            return null;
        }
        try {
            return calibration.calibrateIfDue(mmsi, clock.getAsLong());
        } catch (RuntimeException e) {
            // 校准是增强项：它的异常绝不能让预测这一拍失败
            log.warn("回流校准失败，本拍按下发 null 标定因子继续：mmsi={}", mmsi, e);
            return null;
        }
    }

    /**
     * 广播快照帧；空点列<b>不发帧</b>（MEDIUM.11）。
     *
     * <p>空帧到前端表现为「这条船突然没有轨迹了」，与「这条船本来就不该显示」无法区分；
     * 不发帧时前端保留上一帧，语义明确。此处兜住 {@link PredictionSnapshot#toFrame()} 抛出的
     * 状态异常，因为它是本类可预期的数据状态，不是缺陷。
     */
    private void publish(PredictionSnapshot snapshot) {
        ShipTrajFrame frame;
        try {
            frame = snapshot.toFrame();
        } catch (IllegalStateException emptyPoints) {
            log.warn("快照点列为空，本拍不发 ship_traj：mmsi={} version={}",
                    snapshot.mmsi(), snapshot.version());
            return;
        }
        eventPublisher.publishEvent(new PredictionUpdatedEvent(frame));
    }

    /**
     * 按当前模式算一版快照（含断言网与回落）。
     *
     * <p>精算不可用（冷启动／观测时标不前进）或输出不过网时，<b>本帧</b>回落匀速版：
     * 宁可少一帧精算，也不把没验过的数发出去，更不让该船整帧缺失。
     *
     * @param calibrated 本拍可用的校准结果；{@code null} 表示未装配或不可用
     */
    private PredictionSnapshot buildSnapshot(AisMessage message, long version,
            PredictionCalibrationService.CalibrationOutcome calibrated) {
        if (profile.mode() == PredictionMode.PRECISE) {
            if (profile.engine() == null) {
                // LOW.19：配置说精算、实际没引擎。
                // 第一层防护已经在 PredictionProfile 的构造器里——PRECISE 配 null 引擎会直接抛，
                // 表现为容器启动失败，比"静默跑匀速"好得多。这里保留第二层，是因为那层不变式
                // 若将来被放宽（例如允许运行期热切换 profile），本分支就是唯一还能告警的地方；
                // 同时它也把这一情形计入 preciseRejections，让"精算其实没跑"在指标上可见，
                // 而不是只有 mode() 对外报 PRECISE 这一句孤证。
                if (preciseEngineMissingWarned.compareAndSet(false, true)) {
                    log.warn("预测模式配置为 precise 但精算引擎未装配，将持续回落匀速版"
                            + "（检查 wavenest.m2.prediction.mode 与 PrecisePredictionEngine 的装配；"
                            + "正常情况下 PredictionProfile 的构造器已挡住该状态）");
                }
                preciseRejections.incrementAndGet();
                return constantSnapshot(version, message, calibrated);
            }

            // 11-03 D1：版本号必须先登记再出预测——引擎会用它在「本版点列」上盖版本戳，
            // 下一拍配对残差时才能标出「这条残差对应哪一版快照」。顺序反了会让样本永远
            // 比快照慢一版，而四元组唯一键（mmsi+version+tau+ts）正是靠这个版本号去重。
            profile.engine().recordVersion(message.mmsi(), version);
            Optional<PrecisePredictionEngine.Outcome> computed = profile.engine().predict(message);
            if (computed.isPresent()) {
                PrecisePredictionEngine.Outcome outcome = computed.get();
                Optional<PredictionAssurance.Violation> violation = profile.assurance().check(
                        outcome.t0Ms(), clock.getAsLong(), outcome.validitySec(), outcome.points());
                if (violation.isEmpty()) {
                    return preciseSnapshot(version, outcome, calibrated);
                }
                long rejections = preciseRejections.incrementAndGet();
                log.warn("精算输出未过区间断言，本帧回落匀速版：mmsi={} 断言={} 细节={}（累计 {} 次）",
                        message.mmsi(), violation.get().code().code(), violation.get().detail(), rejections);
            } else {
                log.debug("精算暂不可用（冷启动或观测时标未前进），本帧回落匀速版：mmsi={}",
                        message.mmsi());
            }
        }
        return constantSnapshot(version, message, calibrated);
    }

    /** 匀速版（回退路径）：二维匀速外推 ＋ 固定噪声带。 */
    private PredictionSnapshot constantSnapshot(long version, AisMessage message,
            PredictionCalibrationService.CalibrationOutcome calibrated) {
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
                // zSeries 属精算版：匀速路径一律占位（null 而非空集合，
                // 以便下游区分「无新息」与「有新息但为空」）
                null,
                calibrated == null ? null : calibrated.deviationM(),
                null, null,
                calibrated == null ? null : calibrated.calibrationFactor());

        assertStructure(snapshot, "constant");
        return snapshot;
    }

    /** 精算版：点列、主轴方位、有效期、前提与新息序列都来自引擎，不再用配置常量占位。 */
    private PredictionSnapshot preciseSnapshot(long version, PrecisePredictionEngine.Outcome outcome,
            PredictionCalibrationService.CalibrationOutcome calibrated) {
        PredictionSnapshot snapshot = new PredictionSnapshot(
                outcome.mmsi(),
                version,
                outcome.t0Ms(),
                outcome.courseDeg(),
                outcome.validitySec(),
                outcome.premise().code(),
                outcome.points().stream().map(PredictionService::toFramePoint).toList(),
                outcome.zSeries(),
                calibrated == null ? null : calibrated.deviationM(),
                null, null,
                calibrated == null ? null : calibrated.calibrationFactor());

        // LOW.18：精算路径不再重复调 assertStructure——buildSnapshot 里已用
        // PredictionAssurance.check 判过（check 内含 checkStructure），再判一次只会在
        // 「精算输出违反结构断言」时打出第二条文案为「匀速版…」的误导日志。
        return snapshot;
    }

    /**
     * 匀速版的结构断言（精算版已由 {@link PredictionAssurance#check} 判过，含地理框）。
     *
     * <p>违反即实现缺陷：记 error 仍照发——匀速版是最后兜底，停发会让该船从画面上消失，
     * 比发一条带已知缺陷的轨迹更糟。
     *
     * @param path 产生该快照的路径标识（{@code "constant"}／{@code "precise"}），
     *             写进日志避免把实现缺陷归因到错的路径（LOW.18）
     */
    private void assertStructure(PredictionSnapshot snapshot, String path) {
        Optional<PredictionAssurance.Violation> violation = profile.assurance().checkStructure(
                snapshot.t0Ms(), clock.getAsLong(), snapshot.validitySec(),
                snapshot.points().stream()
                        .map(point -> new EstimatedPoint(point.t(), point.lon(), point.lat(),
                                point.sigmaAlong(), point.sigmaCross()))
                        .toList());
        violation.ifPresent(found -> log.error("{} 路径输出违反结构断言（实现缺陷）：mmsi={} 断言={} 细节={}",
                path, snapshot.mmsi(), found.code().code(), found.detail()));
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
     * 清掉已失联的船（MEDIUM.4：此前四个 Map 只增不删，长期运行内存单调增长）。
     *
     * <p><b>判定口径分两层</b>：
     * <ul>
     *   <li>有快照的船：用快照自身的 {@link PredictionSnapshot#isStale}（起点 ＋ 有效期）——
     *       这是「这版预测还能不能用」的既有口径，不另立一套；</li>
     *   <li>只有观测没有快照的船（理论上不该出现，但重算路径可能先于首帧写入观测）：
     *       用观测时刻对 {@code ttlMs} 判，因为观测本身没有有效期字段。</li>
     * </ul>
     *
     * <p>四个 Map 同步清理：快照、观测、上版航向、版本计数器。版本计数器必须清——
     * 若只清快照留着计数器，船再次出现时版本从旧值继续涨，前端按「版本跳变＝换了一版」的
     * 假设会漏判；清掉后重新从 1 起，与「新会话」的语义一致。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @param ttlMs 观测的存活时长，ms（≤0 时退化为「只按快照有效期判」）
     * @return 被清理的船数
     */
    public int evictStale(long nowMs, long ttlMs) {
        Set<String> stale = new HashSet<>();
        for (Map.Entry<String, PredictionSnapshot> entry : latestSnapshots.entrySet()) {
            if (entry.getValue().isStale(nowMs)) {
                stale.add(entry.getKey());
            }
        }
        if (ttlMs > 0L) {
            for (Map.Entry<String, AisMessage> entry : lastObservations.entrySet()) {
                if (nowMs - entry.getValue().ts() > ttlMs) {
                    stale.add(entry.getKey());
                }
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }
        for (String mmsi : stale) {
            latestSnapshots.remove(mmsi);
            lastObservations.remove(mmsi);
            lastObservedCog.remove(mmsi);
            versionCounters.remove(mmsi);
        }
        log.info("清理失联船舶预测状态：{} 条（{}），剩余在跟踪 {} 条",
                stale.size(), stale, latestSnapshots.size());
        return stale.size();
    }

    /**
     * 用快照有效期作为观测 TTL 的简化重载（惰性清扫走这条）。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 被清理的船数
     */
    public int evictStale(long nowMs) {
        long ttlMs;
        try {
            ttlMs = Math.multiplyExact(settings.validitySec(), 1000L);
        } catch (ArithmeticException overflow) {
            ttlMs = Long.MAX_VALUE;
        }
        return evictStale(nowMs, ttlMs);
    }

    /**
     * 把一条<b>实测</b> AIS 观测配成 E 组 6b 判据所需的样本（解锁吴可欣的闭环枢纽）。
     *
     * <p>{@code HeadingSample} 要的四个量中，{@code predictedTangentDeg}（预测航迹切向）
     * 只能由本模块给出——{@code algo.inflight.HeadingSample} 的 javadoc 明写
     * 「预测切向最终来自 A 组 {@code PredictionSnapshot}，且必须经 M2 常驻层取用完整对象，
     * <b>不得从 WS 帧 {@code ship_traj} 反推</b>」。本方法即那个「常驻层取用」的唯一出口。
     *
     * <p><b>MEDIUM.47：切向取不到时返回空，不拿主轴方位顶替</b>。精算快照的 {@code courseDeg}
     * 是<b>参考线在该处的走向</b>，不是船的航迹切向；船与参考线不平行时两者相差正是夹角，
     * 拿它当 {@code predictedTangentDeg} 会让 E 组 6b 的成向判定带上系统性偏差。
     * 空值让判据本拍跳过（样本缺一条），比喂一个语义错位的数安全。
     *
     * @param measured 实测船位（同时被 {@link #refresh} 刷新过快照时配对才有意义）
     * @return 配对样本；该船尚无快照、或点列不足以定出切向时为空（调用方跳过本拍即可）
     */
    public Optional<HeadingSample> toHeadingSample(AisMessage measured) {
        return snapshotOf(measured.mmsi())
                .flatMap(snapshot -> tangentDegAt(snapshot, measured.ts())
                        .map(tangentDeg -> new HeadingSample(
                                measured.cog(),
                                tangentDeg,
                                ConstantVelocityPredictor.knotsToMs(measured.sog()),
                                measured.ts())));
    }

    /**
     * 快照在指定时刻的航迹切向（度，正北 0、顺时针）。
     *
     * <p>取「离该时刻最近的点 → 下一点」的方位；末点用「上一点 → 本点」。
     * <b>取不到就返回空</b>：点列为空、只有一个点、或参与计算的两点重合
     * （{@code bearingDeg} 在 {@code atan2(0,0)} 处给 0，那是"正北"而非"无值"）。
     *
     * @param snapshot 快照
     * @param tsMs     目标时刻，Unix 毫秒
     * @return 切向方位；无法确定时为空
     */
    static Optional<Double> tangentDegAt(PredictionSnapshot snapshot, long tsMs) {
        List<ShipTrajPoint> points = snapshot.points();
        if (points.size() < 2) {
            return Optional.empty();
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

        ShipTrajPoint from;
        ShipTrajPoint to;
        if (nearest + 1 < points.size()) {
            from = points.get(nearest);
            to = points.get(nearest + 1);
        } else {
            from = points.get(nearest - 1);
            to = points.get(nearest);
        }
        return bearingIfDistinct(from, to);
    }

    /** 两点重合（方位无意义）时返回空，否则返回大地方位。 */
    private static Optional<Double> bearingIfDistinct(ShipTrajPoint from, ShipTrajPoint to) {
        if (Math.abs(from.lon() - to.lon()) < TrajectorySnapshotAdapter.COINCIDENT_EPS_DEG
                && Math.abs(from.lat() - to.lat()) < TrajectorySnapshotAdapter.COINCIDENT_EPS_DEG) {
            return Optional.empty();
        }
        return Optional.of(ConstantVelocityPredictor.bearingDeg(
                from.lon(), from.lat(), to.lon(), to.lat()));
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

        // MEDIUM.12：走接口的防护壳，实现方违约也不会把异常送进 AIS 消费线程
        recomputeRequester.notifyRecompute(new RecomputeRequest(
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

    /** 建快照失败时把版本号退回上一步，保证「抛出时不递增版本」的承诺（MEDIUM.46）。 */
    private void rollbackVersion(String mmsi) {
        AtomicLong counter = versionCounters.get(mmsi);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /** 算法层输出点 → WS 帧点：字段语义一致（σ 均为标准差，单位 m）。 */
    private static ShipTrajPoint toFramePoint(EstimatedPoint point) {
        return new ShipTrajPoint(
                point.tMs(), point.lon(), point.lat(), point.sigmaAlongM(), point.sigmaCrossM());
    }
}
