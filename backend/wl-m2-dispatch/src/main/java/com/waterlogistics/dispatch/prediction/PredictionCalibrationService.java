package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.NeesCalibrator;
import com.waterlogistics.algo.estimation.PredictionBiasCalibrator;
import com.waterlogistics.storage.entity.PredErrorSampleEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.PredErrorSampleMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 预测误差回流校准链路（11-02 阶段二 5 · 卢肄文）。
 *
 * <h3>这条链解决什么问题</h3>
 * <p>预测带宽里的 {@code σ∥ = σ₀ + α·τ} 是<b>按仿真标定的模型</b>，真实赣江上的船不会严格服从它。
 * 不回流，误差就只沉淀在日志里、带宽永远按仿真值给；E 组拿 {@code NIS} 判「预测是否可信」时，
 * 分母用的还是那个假定的 σ。本类把「预测 ↔ 实测」的配对残差变成三样东西：
 * <ol>
 *   <li><b>可持久化的样本</b>（{@code pred_error_sample}，跑一批 AIS 后表里就有行）；</li>
 *   <li><b>NEES 标定因子 {@code c}</b>（{@link NeesCalibrator}），随快照 {@code calibrationFactor} 下发；</li>
 *   <li><b>参数建议值</b>（{@link PredictionBiasCalibrator}），回写 {@code sys_param}。</li>
 * </ol>
 *
 * <h3>三个必须守住的口径</h3>
 * <ul>
 *   <li><b>不采信不足量的标定</b>：{@code N < 200} 或 {@code c} 的 95% 区间宽度 {@code > 0.15} 时，
 *       结果<b>不可用</b>（{@link NeesCalibrator#MIN_SAMPLES_FOR_ACCEPTANCE}、
 *       {@link NeesCalibrator#MAX_CONFIDENCE_WIDTH}）——快照里给 {@code null} 而不是给 1.0。
 *       给 1.0 等于宣称「标定过、且中性」，下游会把一个没验过的数当成验过的用；
 *       {@code null} 才是「尚未标定」的诚实表达，且契约字段本就是 {@code Double}。</li>
 *   <li><b>不重复计数</b>：按船维护 {@code sampleTsMs} 水位，只消费严格新于水位的样本。
 *       生产方每拍都会把同一批残差再吐一遍（它是无状态的），不去重就会把 200 个样本
 *       喂成 2000 个，让 {@code confidenceWidth} 虚假收窄——这是最危险的一种「看起来收敛了」。</li>
 *   <li><b>不拖累预测</b>：本链路跑在预测刷新路径上。样本源异常、库写失败、σ 非法，
 *       一律吞在本地记 warn，绝不上抛——预测停摆比校准缺一批样本严重得多。</li>
 * </ul>
 *
 * <h3>为什么 τ=0 的样本只落库不喂校准器</h3>
 * <p>τ=0 处的「预测点」就是实测点本身，残差恒为 0，对 NEES 均值与分桶统计都是纯噪声；
 * 且 {@link PredictionBiasCalibrator.Sample} 的构造器明确要求 {@code tauSec > 0}。
 * 但它仍是「某版本预测在起点对上了实测」的留痕，故照样落库，便于复核配对逻辑。
 */
@Service
public class PredictionCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(PredictionCalibrationService.class);

    /**
     * 库内时间列的时区。
     *
     * <p>与 {@code application.yml} 的 {@code serverTimezone=Asia/Shanghai} 一致。
     * 用<b>固定时区常量</b>而不是 {@code ZoneId.systemDefault()}：本机若配成 UTC，
     * 样本时刻会整体偏 8 小时，分桶统计（按 {@code sample_ts} 切窗口）就全错位，
     * 而这种错位在单机开发时看不出来。
     */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 校准参数键：纵向 α 基准（与主链路同值 0.10 m/s）。 */
    public static final String KEY_ALPHA_ALONG = "predAlphaAlongMs";

    /** 校准参数键：横向 α 基准（与主链路同值 0.03 m/s）。 */
    public static final String KEY_ALPHA_CROSS = "predAlphaCrossMs";

    /** 校准参数键：沿航道速度偏置（回写目标，初值 0）。 */
    public static final String KEY_ALONG_SPEED_BIAS = "predAlongSpeedBiasMs";

    /** 校准参数键：每船校准节流间隔，s。 */
    public static final String KEY_INTERVAL_SEC = "calCalibrateIntervalSec";

    /** 节流间隔缺省值，s：1 Hz 输入下每船每分钟最多校准一次。 */
    static final int DEFAULT_INTERVAL_SEC = 60;

    /** 落库失败连续达到该次数记 error（11-03 D2 · C6 裁定）。 */
    static final int PERSIST_FAILURE_ALERT_THRESHOLD = 3;

    /** 残差样本来源；容器内无实现时为 {@code null}（校准链路整体不可用，不影响预测）。 */
    private final ObjectProvider<ResidualSampleSource> sampleSources;

    /**
     * 直接给定的样本源（单测用；生产恒为 {@code null}）。
     *
     * <p>为什么留这条口子：{@link ObjectProvider} 是 Spring 的容器句柄，单测要实现它
     * 得写一堆无意义的方法；而校准逻辑本身（去重、门槛、回写）与「谁来提供样本」正交。
     * 直接注入一个 lambda 就能验，测试才盯得住真正要守的口径。
     */
    private final ResidualSampleSource fixedSource;

    /** 样本落库出口；单测可传 {@code null} 跳过落库。 */
    private final PredErrorSampleMapper sampleMapper;

    /** 参数读写口（回写建议值并同步缓存）。 */
    private final SysParamLoader sysParamLoader;

    /** NEES 标定器（全局桶；分层标定由调用方另建实例，本类只管全局 c）。 */
    private final NeesCalibrator nees = new NeesCalibrator();

    /** 偏差校准器（按时域 × 船速分桶）。 */
    private final PredictionBiasCalibrator bias;

    /** 每船已消费到的 {@code sampleTsMs} 水位（去重键）。 */
    private final Map<String, Long> consumedUpToTs = new ConcurrentHashMap<>();

    /** 每船上一次校准时刻（节流）。 */
    private final Map<String, Long> lastRunMs = new ConcurrentHashMap<>();

    /** 最近一次 NEES 结果（无样本时为「0 样本、不可采信」）。 */
    private volatile NeesCalibrator.Result latest = new NeesCalibrator().result();

    /** 落库成功条数（联调与判据核对用）。 */
    private final AtomicLong persistedCount = new AtomicLong();

    /** 因唯一键重复而跳过的条数（重启后重放同一批样本的正常结果，不是故障）。 */
    private final AtomicLong duplicateSkippedCount = new AtomicLong();

    /**
     * 连续落库失败次数（成功或跳过即清零）。
     *
     * <p>为什么用「连续」而不是「累计」：偶发单条失败（连接抖动、单行数据异常）不该惊动运维；
     * 连续失败才说明表不见了／库不可写，这类问题会让整条回流校准悄悄停摆，
     * 必须升级成 error 而不是淹没在 warn 里。
     */
    private final AtomicInteger consecutivePersistFailures = new AtomicInteger();

    /**
     * 生产装配用。
     *
     * <p><b>{@code @Autowired} 不能省</b>：本类有多个构造器，Spring 的「唯一构造器自动推断」
     * 只在构造器<b>恰好一个</b>时才生效；一旦有多个而无一标注，容器会退回找无参构造器，
     * 报 {@code No default constructor found}。这个坑在单测里完全看不见——单测直接
     * {@code new}，只有加载 ApplicationContext 的集成测试才会炸，所以必须显式标注。
     */
    @Autowired
    public PredictionCalibrationService(ObjectProvider<ResidualSampleSource> sampleSources,
            PredErrorSampleMapper sampleMapper, SysParamLoader sysParamLoader) {
        this(sampleSources, sampleMapper, sysParamLoader, biasParamsOf(sysParamLoader));
    }

    /** 单测装配用：直接给定分桶参数，不读库。 */
    PredictionCalibrationService(ObjectProvider<ResidualSampleSource> sampleSources,
            PredErrorSampleMapper sampleMapper, SysParamLoader sysParamLoader,
            PredictionBiasCalibrator.Params biasParams) {
        this.sampleSources = sampleSources;
        this.fixedSource = null;
        this.sampleMapper = sampleMapper;
        this.sysParamLoader = sysParamLoader;
        this.bias = new PredictionBiasCalibrator(biasParams);
    }

    /**
     * 单测装配用：直接给定样本源，绕开 Spring 的 {@link ObjectProvider}。
     *
     * <p><b>为什么做成静态工厂而不是构造器重载</b>：{@code ObjectProvider} 继承了
     * {@code ObjectFactory}（函数式接口），所以「{@code () -> samples}」这种 lambda 实参
     * 在两个重载上都「可能适用」，javac 会报「找不到合适的构造器」；静态工厂的参数类型在
     * 调用点即已确定，不需要重载解析去猜。
     *
     * @param source         样本源（可为 {@code null}，表示「实现未装配」）
     * @param sampleMapper   落库出口（可为 {@code null}，跳过落库）
     * @param sysParamLoader 参数读写口（可为 {@code null}，跳过回写与读表）
     * @return 服务实例
     */
    static PredictionCalibrationService forTesting(ResidualSampleSource source,
            PredErrorSampleMapper sampleMapper, SysParamLoader sysParamLoader) {
        return new PredictionCalibrationService(source, sampleMapper, sysParamLoader);
    }

    private PredictionCalibrationService(ResidualSampleSource source,
            PredErrorSampleMapper sampleMapper, SysParamLoader sysParamLoader) {
        this.sampleSources = null;
        this.fixedSource = source;
        this.sampleMapper = sampleMapper;
        this.sysParamLoader = sysParamLoader;
        this.bias = new PredictionBiasCalibrator(biasParamsOf(sysParamLoader));
    }

    /** 解析当前生效的样本源。 */
    private ResidualSampleSource sourceOrNull() {
        if (fixedSource != null) {
            return fixedSource;
        }
        return sampleSources == null ? null : sampleSources.getIfAvailable();
    }

    /**
     * 校准结果（一次消费的产出）。
     *
     * @param newSamples         本次新消费进的样本数（已去重、已剔除 τ≤0 的重计数）
     * @param totalSamples       累计进入 NEES 的样本数
     * @param calibrationFactor  可下发的标定因子；<b>不足量时为 {@code null}</b>
     * @param deviationM         最近一条样本的预测—实测偏差（二维欧氏距离），m；无样本时为 {@code null}
     * @param acceptable         是否达到《80》§5.1 的采信门槛
     * @param confidenceWidth    {@code c} 的 95% 区间宽度
     */
    public record CalibrationOutcome(int newSamples, int totalSamples, Double calibrationFactor,
            Double deviationM, boolean acceptable, double confidenceWidth) {

        /** 完全不可用（无样本源或无样本）。 */
        static CalibrationOutcome unavailable() {
            return new CalibrationOutcome(0, 0, null, null, false, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * 按船校准——<b>带节流</b>，供预测刷新路径每拍调用。
     *
     * <p>节流的理由：1 Hz 输入下每拍都扫一遍样本源、都写库，会把预测热路径的耗时
     * 从「纯计算」拉成「计算 ＋ IO」；而校准是分钟级收敛的过程，60 s 一次足够。
     * 节流命中时直接返回当前结果，快照仍能拿到同一个 {@code calibrationFactor}。
     *
     * @param mmsi  目标船
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 本次校准结果（节流命中时 {@code newSamples = 0}）
     */
    public CalibrationOutcome calibrateIfDue(String mmsi, long nowMs) {
        if (mmsi == null || mmsi.isBlank()) {
            return currentOutcome(null);
        }
        long intervalMs = intervalSec() * 1000L;
        Long last = lastRunMs.get(mmsi);
        if (last != null && nowMs - last < intervalMs) {
            return currentOutcome(recentDeviation.get(mmsi));
        }
        lastRunMs.put(mmsi, nowMs);
        return calibrate(mmsi);
    }

    /** 每船最近一次预测—实测偏差（节流命中时用它填快照，避免偏差字段随节流抖空）。 */
    private final Map<String, Double> recentDeviation = new ConcurrentHashMap<>();

    /**
     * 立即校准一次（不节流），供单测与手动补算调用。
     *
     * @param mmsi 目标船
     * @return 本次校准结果
     */
    public CalibrationOutcome calibrate(String mmsi) {
        ResidualSampleSource source = sourceOrNull();
        if (source == null) {
            // 吴侧实现未装配：不是故障，是排期。记 debug，避免刷屏
            log.debug("残差样本源未装配，校准链路不可用（快照 calibrationFactor 将为 null）");
            return currentOutcome(null);
        }

        List<ResidualSample> raw;
        try {
            raw = source.residualSamplesOf(mmsi);
        } catch (RuntimeException e) {
            // 生产方违约（接口约定「不得抛异常」）：吞掉，预测链不能因校准断掉
            log.warn("残差样本索取失败，本轮跳过校准：mmsi={} 异常={}", mmsi, e.toString());
            return currentOutcome(recentDeviation.get(mmsi));
        }
        if (raw == null || raw.isEmpty()) {
            return currentOutcome(recentDeviation.get(mmsi));
        }

        long watermark = consumedUpToTs.getOrDefault(mmsi, Long.MIN_VALUE);
        List<ResidualSample> fresh = new ArrayList<>();
        for (ResidualSample sample : raw) {
            if (sample != null && sample.sampleTsMs() > watermark) {
                fresh.add(sample);
            }
        }
        // 生产方约定升序，但它是别人写的：自己排一次，避免水位推进依赖对方的顺序正确
        fresh.sort(Comparator.comparingLong(ResidualSample::sampleTsMs));

        int accepted = 0;
        int inserted = 0;
        int duplicated = 0;
        int failed = 0;
        Double deviation = recentDeviation.get(mmsi);
        for (ResidualSample sample : fresh) {
            PersistResult persistResult = persist(sample);
            switch (persistResult) {
                case INSERTED -> inserted++;
                case DUPLICATE -> duplicated++;
                case FAILED -> failed++;
                case NOT_ATTEMPTED -> { }
            }
            watermark = Math.max(watermark, sample.sampleTsMs());
            deviation = Math.hypot(sample.errAlongM(), sample.errCrossM());

            if (sample.tauSec() <= 0) {
                // τ=0 残差恒为 0，不进统计（见类注释）
                continue;
            }
            try {
                nees.add(sample.errAlongM(), sample.errCrossM(),
                        sample.sigmaAlongM(), sample.sigmaCrossM());
                bias.addSample(new PredictionBiasCalibrator.Sample(
                        sample.tauSec(), sample.vAlongMs(),
                        sample.errAlongM(), sample.errCrossM(),
                        sample.sigmaAlongM(), sample.sigmaCrossM()));
                accepted++;
            } catch (IllegalArgumentException e) {
                log.warn("残差样本不合校准器口径，已跳过：mmsi={} τ={}s 原因={}",
                        mmsi, sample.tauSec(), e.getMessage());
            }
        }

        if (!fresh.isEmpty()) {
            consumedUpToTs.put(mmsi, watermark);
        }
        if (deviation != null) {
            recentDeviation.put(mmsi, deviation);
        }
        if (accepted > 0) {
            latest = nees.result();
            publishSuggestions();
        }
        if (!fresh.isEmpty()) {
            // 成功的／跳过的／失败的三个计数成对出现：只看「成功=0」分不清是「没有新样本」
            // 还是「全被唯一键挡了」还是「库写不进去」，三种情况的处置完全不同
            log.info("校准完成：mmsi={} 新样本={} 累计={} c={} 区间宽={} 可采信={} 落库[成功={} 跳过={} 失败={}]",
                    mmsi, accepted, latest.sampleCount(),
                    String.format("%.3f", latest.calibrationFactor()),
                    String.format("%.4f", latest.confidenceWidth()), latest.acceptable(),
                    inserted, duplicated, failed);
        }
        return new CalibrationOutcome(accepted, latest.sampleCount(), factorOrNull(),
                deviation, latest.acceptable(), latest.confidenceWidth());
    }

    /**
     * 当前可下发的标定因子：<b>不足量时为 {@code null}</b>。
     *
     * <p>「不可用」与「等于 1.0」必须区分开。下游（E 组 NIS 判据、看台）看到 {@code null}
     * 才知道该按未标定处理；看到 1.0 会以为标定过且恰好中性。
     */
    public Double calibrationFactorOrNull() {
        return factorOrNull();
    }

    /** 当前 NEES 结果（联调／单测观察用）。 */
    public NeesCalibrator.Result neesResult() {
        return latest;
    }

    /** 当前分桶建议（联调观察用，不触发写库）。 */
    public List<PredictionBiasCalibrator.Suggestion> suggestions() {
        return bias.suggest();
    }

    /** 已落库样本条数。 */
    public long persistedSampleCount() {
        return persistedCount.get();
    }

    /** 因四元组重复被唯一键挡下的条数（重启重放的正常结果）。 */
    public long duplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }

    /** 当前连续落库失败次数（成功或跳过即清零）。 */
    public int consecutivePersistFailures() {
        return consecutivePersistFailures.get();
    }

    /** 清空内存态（复标前用；不动已落库的样本）。 */
    public void reset() {
        nees.reset();
        latest = new NeesCalibrator().result();
        consumedUpToTs.clear();
        lastRunMs.clear();
        recentDeviation.clear();
        consecutivePersistFailures.set(0);
    }

    private CalibrationOutcome currentOutcome(Double deviation) {
        return new CalibrationOutcome(0, latest.sampleCount(), factorOrNull(), deviation,
                latest.acceptable(), latest.confidenceWidth());
    }

    private Double factorOrNull() {
        return latest.acceptable() ? latest.calibrationFactor() : null;
    }

    /** 单条样本的落库结果（按轮汇总成日志里的三个计数）。 */
    enum PersistResult {

        /** 新插入一行。 */
        INSERTED,

        /** 四元组已存在，被唯一键挡下——正常现象，不是故障。 */
        DUPLICATE,

        /** 落库失败（表不存在、库不可写等）。 */
        FAILED,

        /** 没有库出口（单测装配），什么都没做。 */
        NOT_ATTEMPTED
    }

    /**
     * 落库一条样本：<b>重复键记「跳过」、真失败才计数</b>（11-03 D2）。
     *
     * <h3>为什么重复要单独成一类</h3>
     * <p>本类的去重水位（{@link #consumedUpToTs}）住在内存里，<b>重启即清零</b>；
     * 而生产方（引擎）是无状态地吐「最近一窗」样本，重启后第一轮必定把同一批样本再吐一遍。
     * 这时唯一键 {@code uk_pes_pair} 会拒掉它们——若把这种拒绝算作「失败」，
     * 每次重启都会触发连续 3 次失败的 error 告警，真正的故障反而没人信。
     * 故重复键既不计失败、<b>还要清零连续失败计数</b>：它证明「库是通的、表是有的」。
     *
     * <h3>为什么失败要连续计数</h3>
     * <p>偶发单条失败（连接抖动）不该惊动运维；连续 3 次说明表不见了或库不可写，
     * 而这类故障的表现是「校准永远不生效」，只有 error 级别才能被看见。
     * 到阈值时打一次 error（越界点），之后降回 warn 带累计次数——避免库长时间不可用时刷屏。
     *
     * @param sample 待落库样本
     * @return 落库结果
     */
    private PersistResult persist(ResidualSample sample) {
        if (sampleMapper == null) {
            return PersistResult.NOT_ATTEMPTED;
        }
        try {
            PredErrorSampleEntity row = new PredErrorSampleEntity();
            row.setShipMmsi(sample.mmsi());
            row.setPredVersion(sample.predVersion());
            row.setTauSec(sample.tauSec());
            row.setErrAlongM(sample.errAlongM());
            row.setErrCrossM(sample.errCrossM());
            row.setErrCourseDeg(sample.errCourseDeg());
            row.setSigmaAlongM(sample.sigmaAlongM());
            row.setSigmaCrossM(sample.sigmaCrossM());
            row.setVAlongMs(sample.vAlongMs());
            row.setSampleTs(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(sample.sampleTsMs()), DB_ZONE));
            sampleMapper.insert(row);
            persistedCount.incrementAndGet();
            consecutivePersistFailures.set(0);
            return PersistResult.INSERTED;
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                duplicateSkippedCount.incrementAndGet();
                consecutivePersistFailures.set(0);
                log.debug("误差样本已存在，跳过：mmsi={} 版本={} τ={}s ts={}",
                        sample.mmsi(), sample.predVersion(), sample.tauSec(), sample.sampleTsMs());
                return PersistResult.DUPLICATE;
            }
            int failures = consecutivePersistFailures.incrementAndGet();
            if (failures == PERSIST_FAILURE_ALERT_THRESHOLD) {
                log.error("误差样本连续 {} 次落库失败——回流校准已停止沉淀，"
                        + "请检查 007 是否执行、库是否可写（mmsi={} 异常={}）",
                        failures, sample.mmsi(), e.toString(), e);
            } else if (failures < PERSIST_FAILURE_ALERT_THRESHOLD) {
                log.warn("误差样本落库失败，已跳过：mmsi={} 版本={} ts={} 异常={}",
                        sample.mmsi(), sample.predVersion(), sample.sampleTsMs(), e.toString());
            } else {
                log.warn("误差样本落库仍失败（已连续 {} 次）：mmsi={} 异常={}",
                        failures, sample.mmsi(), e.toString());
            }
            return PersistResult.FAILED;
        }
    }

    /**
     * 判定异常是否为「唯一键冲突」。
     *
     * <p>为什么不能只 catch {@code DuplicateKeyException}：Spring 的异常转换依赖
     * {@code MyBatisExceptionTranslator} 能否把 {@code SQLException} 认出来；接了自定义
     * 数据源代理、或在没有 Spring 上下文的地方直接调 Mapper 时，抛出来的是 MyBatis 的
     * {@code PersistenceException}。故再顺一次 cause 链，按 SQLState（{@code 23000}）
     * 与错误码（MySQL {@code 1062}）兜底——按错误码判，比按错误文案判稳。
     */
    private static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            if (current instanceof java.sql.SQLIntegrityConstraintViolationException sql
                    && "23000".equals(sql.getSQLState())) {
                return true;
            }
            if (current instanceof java.sql.SQLException sql
                    && sql.getErrorCode() == 1062) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 把分桶建议按类别聚合成<b>一条</b>参数值并回写 {@code sys_param}。
     *
     * <p><b>为什么按样本数加权而不是取最大桶</b>：不同时域／船速桶的建议值可能互相矛盾
     * （近距桶说放宽、远距桶说收紧），取最大桶会让「样本多的那个桶恰好样本少」的巧合
     * 决定全局参数；加权平均是同一层内方差最小的合并方式，且与合并后的有效样本量一致。
     *
     * <p><b>写失败不改内存</b>：{@link SysParamLoader#put} 返回 false 或抛异常时只记 warn，
     * {@code sys_param} 保持旧值——参数表是全局共享的，写坏一次影响所有人。
     */
    private void publishSuggestions() {
        List<PredictionBiasCalibrator.Suggestion> all = bias.suggest();
        if (all.isEmpty()) {
            return;
        }
        Map<PredictionBiasCalibrator.Kind, double[]> weighted = new EnumMap<>(
                PredictionBiasCalibrator.Kind.class);
        for (PredictionBiasCalibrator.Suggestion suggestion : all) {
            double[] acc = weighted.computeIfAbsent(suggestion.kind(), key -> new double[2]);
            acc[0] += suggestion.suggestedValue() * suggestion.sampleCount();
            acc[1] += suggestion.sampleCount();
        }
        for (Map.Entry<PredictionBiasCalibrator.Kind, double[]> entry : weighted.entrySet()) {
            double weight = entry.getValue()[1];
            if (weight <= 0.0) {
                continue;
            }
            double value = entry.getValue()[0] / weight;
            String key = sysParamKeyOf(entry.getKey());
            if (key == null || sysParamLoader == null) {
                continue;
            }
            try {
                boolean written = sysParamLoader.put(key, String.format("%.6f", value),
                        "阈值", "预测校准器回写（80 §3.2.10；" + entry.getKey().code() + "）");
                if (!written) {
                    log.warn("校准建议回写被拒：键={} 值={}（键或值非法）", key, value);
                }
            } catch (RuntimeException e) {
                log.warn("校准建议回写失败：键={} 值={} 异常={}", key, value, e.toString());
            }
        }
    }

    private static String sysParamKeyOf(PredictionBiasCalibrator.Kind kind) {
        return switch (kind) {
            case ALONG_SPEED_BIAS -> KEY_ALONG_SPEED_BIAS;
            case ALPHA_ALONG_WIDEN -> KEY_ALPHA_ALONG;
            case ALPHA_CROSS_WIDEN, ALPHA_CROSS_SHRINK -> KEY_ALPHA_CROSS;
        };
    }

    private long intervalSec() {
        if (sysParamLoader == null) {
            return DEFAULT_INTERVAL_SEC;
        }
        int configured = sysParamLoader.getInt(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        // 钳到正整数：0 或负数会让节流失效，等于每拍写一次库
        return configured > 0 ? configured : DEFAULT_INTERVAL_SEC;
    }

    /**
     * 从 {@code sys_param} 组装分桶参数；缺键时回落 {@link PredictionBiasCalibrator.Params#defaults()}
     * 的 C5 定稿基准（{@code α_along = 0.10}、{@code α_cross = 0.03}，分别与 002 主链路的
     * {@code alphaAlongMs}／{@code alphaCrossMs} 及 {@code predAlphaAlongMs}／{@code predAlphaCrossMs} 同值）。
     *
     * <p>只覆盖三个随时间变化的键（分桶宽／α 基准），其余沿用
     * {@link PredictionBiasCalibrator.Params#defaults()}——它们是算法结构参数，
     * 不是现场可调阈值，配成可改只会让「同一批数据跑出两种结论」。
     */
    private static PredictionBiasCalibrator.Params biasParamsOf(SysParamLoader loader) {
        PredictionBiasCalibrator.Params defaults = PredictionBiasCalibrator.Params.defaults();
        if (loader == null) {
            return defaults;
        }
        double alphaAlong = loader.getDouble(KEY_ALPHA_ALONG, defaults.currentAlphaAlongMs());
        double alphaCross = loader.getDouble(KEY_ALPHA_CROSS, defaults.currentAlphaCrossMs());
        double bucketSec = loader.getDouble("calTimeBucketSec", defaults.calTimeBucketSec());
        int minSamples = loader.getInt("calMinSamples", defaults.calMinSamples());
        int windowN = loader.getInt("calWindowN", defaults.calWindowN());
        int persistM = loader.getInt("calPersistM", defaults.calPersistM());
        return new PredictionBiasCalibrator.Params(
                bucketSec > 0 ? bucketSec : defaults.calTimeBucketSec(),
                defaults.timeBucketCount(),
                defaults.speedBucketEdgesMs(),
                windowN > 0 ? windowN : defaults.calWindowN(),
                minSamples > 0 ? minSamples : defaults.calMinSamples(),
                persistM > 0 ? persistM : defaults.calPersistM(),
                defaults.kBias(), defaults.kBand(), defaults.kTight(),
                defaults.maxAdjustRatio(), defaults.maxVBiasMs(),
                alphaAlong >= 0 ? alphaAlong : defaults.currentAlphaAlongMs(),
                alphaCross >= 0 ? alphaCross : defaults.currentAlphaCrossMs());
    }
}
