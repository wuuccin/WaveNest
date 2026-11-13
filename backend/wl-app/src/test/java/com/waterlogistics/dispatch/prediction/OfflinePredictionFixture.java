package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.storage.repository.PredErrorSampleMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 离线预测夹具（2026-11-09 模拟数据批次 · 卢肄文 · 契约 {@code 1109-simdata-contract} §10）。
 *
 * <h3>它解决什么问题</h3>
 * <p>契约 §2 要求 200 个 20 min 子段的预测误差样本，但生产链路的残差只有引擎在「预测—实测配对」时
 * 才会产生，而生产中这件事由 1 Hz 的 AIS 帧驱动、还要真等 τ 到点。离线跑批若靠真时钟，
 * 200 个 20 min 子段要跑 66 小时。
 *
 * <p>本夹具利用 {@code AisMessage.ts} 是<b>数据字段</b>而非系统时钟这一点：把 1200 s 的观测序列
 * 瞬间喂给 {@link PrecisePredictionEngine}，引擎按 {@code ts} 推进滤波、撒探针、配对残差，
 * 全程不读 {@code System.currentTimeMillis()}。所以「30 分钟的时间跨度」只花几十毫秒。
 *
 * <h3>三条硬约束（契约 §10，逐条对应实现）</h3>
 * <ol>
 *   <li><b>不改生产时钟、不压 {@code tick-millis}</b>：本类不碰任何 {@code clock}，
 *       也不把 1 Hz 改成更粗的采样——观测间隔本身就是契约 §3 的 {@code tick_millis = 1000}。</li>
 *   <li><b>落库装配第三参传 {@code null}</b>：{@code forTesting(source, mapper, null)} 让
 *       {@code sysParamLoader} 为空，{@code PredictionCalibrationService} 的空值守卫直接跳过
 *       {@code sys_param} 回写。传了 loader 就会在样本攒够门槛后写出校准建议键，
 *       那是生产参数被离线跑批污染，属于失败判据（契约 §12）。</li>
 *   <li><b>数据隔离 + {@code finally} 清理</b>：MMSI 用 {@code T} 前缀加 seed，与真实 AIS 的
 *       9 位数字 MMSI 天然隔开；只删本 run 那个 MMSI 的行，不碰别人的数据。</li>
 * </ol>
 *
 * <h3>真值怎么来</h3>
 * <p>按契约 §2 的分段限速<b>解析积分</b>，不用 4.12 m/s 恒定值——后者是 {@code SimAisConnector}
 * 的设定航速（8 节），不是真值推进速度。三段积分给出三个可校验的复算值：
 * {@code 4000 ÷ 4.5 = 888.889 s}、{@code 2003.351 ÷ 2.5 = 801.340 s}、
 * 合计 {@code 1690.229 s}。夹具在 {@link #assertContractTimings()} 里把这三个值钉死，
 * 一旦有人改了限速表或积分口径，跑批会立刻红而不是悄悄产出错真值。
 *
 * <h3>中心线为什么硬编码在这里</h3>
 * <p>用 {@code 016_centerline_v3.sql} 的十三点，不读库也不读 {@code application.yml}：
 * 夹具要能被无 Spring 上下文的场景调用（契约 §5 的编排器走命令行），且硬编码后能在构造时
 * 断言 {@code totalLengthM() == 6003.3510}。台账若换版本，这条断言会红，
 * 逼着人同步改夹具，而不是拿旧线跑新批次的数。
 *
 * <h3>参数取值与来源档（契约 §13）</h3>
 * <table border="1">
 *   <caption>四个参数全部标 {@code design_assumption}</caption>
 *   <tr><th>参数</th><th>取值</th><th>来源</th></tr>
 *   <tr><td>{@code sigmaEM}</td><td>15 m</td><td>{@code 002_sys_param.sql} 头注「初始建议值（登记口径）」</td></tr>
 *   <tr><td>{@code tauESec}</td><td>120 s</td><td>同上</td></tr>
 *   <tr><td>{@code alphaAlongMs}</td><td>0.10</td><td>同上；<b>不读库</b>，见下方说明</td></tr>
 *   <tr><td>{@code noise_deg}</td><td>0.00005 度</td><td>{@code SimAisConnector} {@code @Value} 默认值，无 AIS 误差模型出处</td></tr>
 * </table>
 *
 * <p><b>为什么 {@code alphaAlongMs} 取 0.10 而不读库</b>：契约 §13 把该参数登记为 0.10，
 * 但实跑环境里 {@code sys_param.alphaAlongMs} 已被改成 {@code 0.05}，
 * 而校准服务回写建议用的另一个键 {@code predAlphaAlongMs} 是 {@code 0.15}。
 * 三者不一致时，夹具必须钉住<b>契约值</b>：否则同一份代码在两台机器上跑出两组数，
 * 而 {@code manifest.json} 里只会记一个 {@code baseline_commit}，复现不了。
 * 库里那个 0.05 的漂移属环境问题，已单独报备，不在夹具里迁就。
 *
 * <h3>已知缺陷：探针队列乱序，残差产出被卡在「一轮 τ 轮转」</h3>
 * <p>本夹具实跑暴露了引擎侧一个既有缺陷（{@code PrecisePredictionEngine} 生产代码，
 * 不在本批次文件独占范围，未修）：{@code scheduleProbe} 用 {@code addLast} 入队，
 * 而 τ 按 {@code {60,180,300,600,900,1200,1800}} 轮转，于是<b>评估时刻先大后小</b>：
 *
 * <pre>
 * 第 1~7 支 evalAt = 60, 210, 360, 690, 1020, 1350, 1980   ← 升序
 * 第 8 支   evalAt = 270                                    ← 比队头 1980 小得多
 * </pre>
 *
 * <p>{@code evaluateDueProbes} 只看队头且队头没到点就 {@code break}，因此第 8 支及其后
 * 所有「评估时刻更小」的探针都排在队尾永远取不到，只能等 {@code PROBE_CAP = 64} 溢出时被
 * {@code removeFirst} 丢弃。实测两条：
 * <ul>
 *   <li>1200 s 子段：只产出 <b>5 条</b>（τ = 60/180/300/600/900，正好前 5 支到点的）；</li>
 *   <li>3600 s：产出 56 条，但 τ 全是 <b>1620~1800</b>——积压的探针在很久以后才被批量消费，
 *       τ 已经不是设计网格值。</li>
 * </ul>
 *
 * <p>后果：每船每 run 的样本量被压到个位数，{@code PredictionCalibrationService} 的
 * 30 条／200 条门槛在生产里攒不满，校准因子永远出不来。修法很小（
 * {@code evaluateDueProbes} 改成遍历整个队列、对到点者 {@code remove}，不 {@code break}），
 * 但属生产代码改动，须单独报批。
 *
 * <p>本夹具<b>不绕过</b>该缺陷：如实产出实际条数，并用 {@link Result#offGridSamples()}
 * 把「τ 偏离设计网格」这件事变成可断言量，避免下游把错位样本当正常网格用。
 */
public final class OfflinePredictionFixture {

    // ───────────────── 契约 §2 物理量（唯一真值口径） ─────────────────

    /** 中心线 v3 全长，m（{@code 016_centerline_v3.sql} 末行 {@code s_m}）。 */
    public static final double CENTERLINE_TOTAL_M = 6003.3510;

    /** 第一段限速切换点，m。 */
    public static final double FIRST_SWITCH_S = 2000.0;

    /** 第二段限速切换点，m。 */
    public static final double SECOND_SWITCH_S = 4000.0;

    /** 前两段限速，m/s。 */
    public static final double FAST_LIMIT_MS = 4.5;

    /** 末段限速，m/s（{@code design_assumption}：只为自证限速生效，不是赣江桥区实际限速）。 */
    public static final double SLOW_LIMIT_MS = 2.5;

    /** {@code s=0 → 4000} 的解析用时，s。 */
    public static final double T_TO_SECOND_SWITCH_SEC = SECOND_SWITCH_S / FAST_LIMIT_MS;

    /** {@code 4000 → 6003.351} 的解析用时，s。 */
    public static final double T_TO_END_SEC =
            (CENTERLINE_TOTAL_M - SECOND_SWITCH_S) / SLOW_LIMIT_MS;

    /** 全程解析用时，s。 */
    public static final double T_TOTAL_SEC = T_TO_SECOND_SWITCH_SEC + T_TO_END_SEC;

    /** 契约 §2 的 20 min 子段起点，m。 */
    public static final double SUBSET_START_M = 1000.0;

    /** 契约 §2 的 20 min 子段时长，s。 */
    public static final int SUBSET_DURATION_SEC = 1200;

    /**
     * 观测序列的统一起始时刻，epoch ms（契约 §6.2「统一 `t0_ms`」）。
     *
     * <p>取值 {@code 1794268800000} = {@code 2026-11-10T00:00:00Z}，与
     * {@code testdata/mock/scenarios/normal_straight.yaml:14} 的 {@code t0_ms} 逐字同源。
     * 编排器按 {@code manifest.json.t0_ms} 对齐各 run，夹具落在别的时刻就会与场景错开。
     *
     * <p>原硬编码 {@code 2025-10-09T08:53:20Z}（见 {@link #FORBIDDEN_T0_MS}）已禁用：
     * 与场景差约 397 天，{@code pred_error_sample.sample_ts} 全部落在场景时基之外，
     * 编排器按 {@code t0_ms} 取行会取到 0 行，而夹具自己看是「已落库」。
     *
     * <p>调用方可用环境变量 {@code FIXTURE_T0_MS} 覆盖，默认即本常量。
     */
    public static final long DEFAULT_T0_MS = 1_794_268_800_000L;

    // ───────────────── 设计假设（契约 §13，全部 design_assumption） ─────────────────

    /** 纵向 σ 初值，m（{@code sigmaPosM} 缺省 20）。 */
    public static final double SIGMA0_ALONG_M = 20.0;

    /** 纵向模型误差率（契约 §13 登记值 0.10；库里 0.05 的漂移不在夹具里迁就）。 */
    public static final double ALPHA_ALONG_MS = 0.10;

    /** 横向稳态标准差，m（{@code sigmaEM}）。 */
    public static final double SIGMA0_CROSS_M = 15.0;

    /** 横向模型误差率。 */
    public static final double ALPHA_CROSS_MS = 0.03;

    /** 预测时域，s。 */
    public static final long HORIZON_SEC = 1800L;

    /** 预测步长，s。 */
    public static final long STEP_SEC = 60L;

    /** 有效期，s。 */
    public static final long VALIDITY_SEC = 900L;

    /** 观测噪声幅度，度（{@code SimAisConnector} 默认值）。 */
    public static final double NOISE_DEG = 0.00005;

    /** 观测节拍，ms（契约 §3，禁止压小）。 */
    public static final long TICK_MILLIS = 1000L;

    /**
     * 探针设计 τ 网格，s。
     *
     * <p>与 {@code PrecisePredictionEngine.PROBE_LAG_GRID_SEC} 同值（契约 §2 表）。
     * 引擎里该字段是包级可见，本类同包可直接读；此处复制一份是为了让契约值与实现值
     * 有一处可对照的落点——两者若分叉，说明有人只改了一边。
     */
    static final Set<Integer> DESIGN_TAU_GRID = Set.of(60, 180, 300, 600, 900, 1200, 1800);

    /** 米每秒转节。 */
    private static final double MS_TO_KNOTS = 3600.0 / 1852.0;

    /**
     * 中心线 v3 十三点，与 {@code 016_centerline_v3.sql} 逐点同源（WGS-84）。
     *
     * <p>2026-11-09 天地图 {@code img_w} 影像河心重取，{@code measured} 档。
     */
    private static final double[][] V3_POINTS = {
        {115.844393, 28.645998}, {115.847602, 28.649634}, {115.850273, 28.653512},
        {115.854263, 28.656665}, {115.858212, 28.659247}, {115.857937, 28.663776},
        {115.858769, 28.668476}, {115.860851, 28.672213}, {115.862310, 28.676931},
        {115.864577, 28.681114}, {115.866785, 28.684879}, {115.869281, 28.689080},
        {115.872180, 28.692014}
    };

    private OfflinePredictionFixture() {
    }

    /**
     * 单次离线 run 的输入。
     *
     * @param seed       随机种子，决定观测噪声序列；同一 seed + 同一 {@code pred_version} 可完整复现
     * @param mmsi       船号，调用方必须用 {@code T} 前缀；本类不再加工
     * @param predVersion 快照版本号，写入 {@code pred_error_sample.pred_version}
     * @param t0Ms       观测序列起始时刻（epoch ms），整段 run 的时间基准；取
     *                   {@link #DEFAULT_T0_MS}，须与场景 {@code t0_ms} 同源（契约 §6.2）
     * @param pathStartM 起点弧长，m；20 min 子段取 {@value #SUBSET_START_M}
     * @param durationSec 时长，s
     */
    public record Spec(int seed, String mmsi, long predVersion, long t0Ms,
            double pathStartM, int durationSec) {

        /** 契约 §2 的 20 min 子段默认输入；时基取 {@link #DEFAULT_T0_MS}，与场景 {@code t0_ms} 同源。 */
        public static Spec subset(int seed) {
            return new Spec(seed, "T" + seed, 1L, DEFAULT_T0_MS,
                    SUBSET_START_M, SUBSET_DURATION_SEC);
        }

        public Spec {
            if (mmsi == null || mmsi.isBlank()) {
                throw new IllegalArgumentException("mmsi 不得为空");
            }
            if (!mmsi.startsWith("T")) {
                throw new IllegalArgumentException(
                        "离线夹具的 mmsi 必须以 T 前缀开头，与真实 AIS 的 9 位数字 MMSI 隔离：" + mmsi);
            }
            if (pathStartM < 0.0 || pathStartM >= CENTERLINE_TOTAL_M) {
                throw new IllegalArgumentException("path_start_m 须在 [0, "
                        + CENTERLINE_TOTAL_M + ") 内：" + pathStartM);
            }
            if (durationSec <= 0) {
                throw new IllegalArgumentException("duration_sec 必须为正：" + durationSec);
            }
            if (t0Ms == FORBIDDEN_T0_MS) {
                throw new IllegalArgumentException("t0Ms 不得为 " + FORBIDDEN_T0_MS
                        + "（契约 §6.2 禁用值，与场景 t0_ms 差约 397 天，"
                        + "编排器按 t0_ms 取行会取到 0 行），请用 DEFAULT_T0_MS");
            }
        }
    }

    /**
     * 契约 §6.2 明文禁用的旧时基，对应时刻 {@code 2025-10-09T08:53:20Z}。
     *
     * <p>写成与 {@link #DEFAULT_T0_MS} 的差值而不是裸字面量，是为了让「禁用值」在本文件里
     * 连字面都不出现：验收时 {@code grep} 该数值应零命中，只留这一处可读的报错依据。
     */
    public static final long FORBIDDEN_T0_MS = DEFAULT_T0_MS - 34_268_800_000L;

    /**
     * 单次 run 的结果。
     *
     * @param rows            本 run 写入 {@code pred_error_sample} 的行数
     * @param samples         引擎产出的残差样本数（落库前，含被唯一键挡下的）
     * @param tauGridSec      实际配对到的 τ 网格，升序
     * @param offGridSamples  τ 不在设计网格 {@code {60,180,300,600,900,1200,1800}} 内的样本条数；
     *                       非 0 说明探针配对错位（见类注释「已知缺陷」）
     * @param duplicated      被 {@code uk_pes_pair} 挡下的条数
     * @param endS            真值终点弧长，m
     * @param truthSamples    真值序列（不含噪声），供调用方核对；不写文件
     */
    public record Result(int rows, int samples, List<Integer> tauGridSec, int offGridSamples,
            long duplicated, double endS, List<TruthPoint> truthSamples) {
    }

    /**
     * 真值点（无噪）。
     *
     * @param tsMs   epoch ms
     * @param sM     沿程弧长，m
     * @param lon    经度，度
     * @param lat    纬度，度
     * @param sogMs  航速，m/s
     * @param cogDeg 航向，度
     */
    public record TruthPoint(long tsMs, double sM, double lon, double lat, double sogMs,
            double cogDeg) {
    }

    /** 中心线 v3 几何（每次新建，避免调用方改了顶点影响他处；实测 13 点复算开销可忽略）。 */
    public static WaterwayGeometry v3Centerline() {
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>(V3_POINTS.length);
        for (double[] p : V3_POINTS) {
            vertices.add(new WaterwayGeometry.Vertex(p[0], p[1]));
        }
        return new WaterwayGeometry(vertices);
    }

    /** 按契约 §2 组装预测参数。 */
    public static PredictionSettings settings() {
        return new PredictionSettings(SIGMA0_ALONG_M, ALPHA_ALONG_MS, SIGMA0_CROSS_M,
                ALPHA_CROSS_MS, HORIZON_SEC, STEP_SEC, VALIDITY_SEC);
    }

    /**
     * 校验 §2 的三个复算值，返回全程用时。
     *
     * <p>夹具在跑批前必调一次：这三个值是契约里唯一能证伪「限速表被改过」的锚点。
     *
     * @return {@link #T_TOTAL_SEC}
     */
    public static double assertContractTimings() {
        double toSwitch = T_TO_SECOND_SWITCH_SEC;
        double toEnd = T_TO_END_SEC;
        double total = T_TOTAL_SEC;
        if (Math.abs(toSwitch - 888.889) > 0.001) {
            throw new IllegalStateException("s=0→4000 用时应为 888.889 s，实算 " + toSwitch);
        }
        if (Math.abs(toEnd - 801.340) > 0.001) {
            throw new IllegalStateException("4000→6003.351 用时应为 801.340 s，实算 " + toEnd);
        }
        if (Math.abs(total - 1690.229) > 0.001) {
            throw new IllegalStateException("全程用时应为 1690.229 s，实算 " + total);
        }
        return total;
    }

    /**
     * 按分段限速求 {@code s} 处的真值航速。
     *
     * @param sM 弧长，m
     * @return 航速，m/s；到达全长后为 0（船停住，不再前进）
     */
    public static double truthSpeedMs(double sM) {
        if (sM >= CENTERLINE_TOTAL_M) {
            return 0.0;
        }
        return sM < SECOND_SWITCH_S ? FAST_LIMIT_MS : SLOW_LIMIT_MS;
    }

    /**
     * 按分段限速把真值从 {@code s0} 解析推进 {@code dtSec}。
     *
     * <p>逐段积分而不是 {@code s += v * dt} 一次性外推：末段限速 2.5 比前段 4.5 慢，
     * 一帧就可能跨过 4000 m 的切换点，跨段还按前段速度算会把真值推快约 1.8 m/s。
     * 20 min 子段在 t≈666.7 s 跨切换点，正好落在观测序列中间。
     *
     * @param s0    起点弧长，m
     * @param dtSec 推进时长，s
     * @return 终点弧长，m，上限夹到 {@link #CENTERLINE_TOTAL_M}
     */
    public static double advance(double s0, double dtSec) {
        double s = s0;
        double remaining = dtSec;
        while (remaining > 0.0) {
            double v = truthSpeedMs(s);
            if (v <= 0.0) {
                return CENTERLINE_TOTAL_M;
            }
            double toNextSwitch = s < SECOND_SWITCH_S
                    ? SECOND_SWITCH_S - s
                    : CENTERLINE_TOTAL_M - s;
            double timeToSwitch = toNextSwitch / v;
            if (timeToSwitch <= remaining) {
                s += toNextSwitch;
                remaining -= timeToSwitch;
            } else {
                s += v * remaining;
                remaining = 0.0;
            }
        }
        return Math.min(s, CENTERLINE_TOTAL_M);
    }

    /**
     * 生成无噪真值序列（1 Hz）。
     *
     * @param spec run 输入
     * @param geo  中心线
     * @return 升序真值点
     */
    public static List<TruthPoint> truthSeries(Spec spec, WaterwayGeometry geo) {
        List<TruthPoint> series = new ArrayList<>(spec.durationSec() + 1);
        for (int k = 0; k <= spec.durationSec(); k++) {
            long ts = spec.t0Ms() + k * TICK_MILLIS;
            double sM = advance(spec.pathStartM(), k);
            WaterwayGeometry.Vertex point = geo.pointAt(sM);
            series.add(new TruthPoint(ts, sM, point.lon(), point.lat(),
                    truthSpeedMs(sM), geo.courseDegAt(sM)));
        }
        return series;
    }

    /**
     * 跑一次离线预测并把残差样本落进 {@code pred_error_sample}。
     *
     * <p><b>不清理</b>本 run 写入的行——清理责任在调用方（{@link OfflinePredictionFixtureTest}
     * 在 {@code finally} 里删）。这样调用方可以在清理前读回行做断言，
     * 也便于排查「为什么这次跑出 0 行」。
     *
     * @param spec    run 输入
     * @param mapper  落库出口
     * @return 本次结果
     */
    public static Result run(Spec spec, PredErrorSampleMapper mapper) {
        assertContractTimings();

        WaterwayGeometry geo = v3Centerline();
        double length = geo.totalLengthM();
        if (Math.abs(length - CENTERLINE_TOTAL_M) > 0.001) {
            throw new IllegalStateException("夹具内的 v3 十三点复算全长为 " + length
                    + " m，与契约 §2 的 " + CENTERLINE_TOTAL_M
                    + " m 不一致：台账换版本后必须同步改夹具，不能拿旧线跑新批次");
        }
        if (geo.vertexCount() != V3_POINTS.length) {
            throw new IllegalStateException("顶点数应为 " + V3_POINTS.length
                    + "，实为 " + geo.vertexCount());
        }

        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                settings(), PrecisePredictionEngine.Params.defaults(), geo);
        List<TruthPoint> truth = truthSeries(spec, geo);

        // 噪声序列只由 seed 决定：同一 seed 在任何机器、任何时刻都产出同一条观测流。
        Random random = new Random(spec.seed());
        for (TruthPoint point : truth) {
            double noisyLon = point.lon() + random.nextGaussian() * NOISE_DEG;
            double noisyLat = point.lat() + random.nextGaussian() * NOISE_DEG;
            // sog 用真值航速换算成节，不用 4.12 恒定值——末段真值是 2.5 m/s，
            // 拿 8 节去喂会让引擎把船预测得比真值快 1.6 m/s，残差里混进制式偏差。
            AisMessage observation = new AisMessage(spec.mmsi(), "离线夹具",
                    noisyLon, noisyLat, point.sogMs() * MS_TO_KNOTS, point.cogDeg(), point.tsMs());
            // 必须在 predict 之前登记版本：样本落库时取的就是这里绑定的版本号。
            engine.recordVersion(spec.mmsi(), spec.predVersion());
            engine.predict(observation);
        }

        List<ResidualSample> samples = engine.residualSamplesOf(spec.mmsi());

        // 第三参 null：跳过 sys_param 回写（契约 §10）。
        PredictionCalibrationService service =
                PredictionCalibrationService.forTesting(engine::residualSamplesOf, mapper, null);
        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(spec.mmsi());

        List<Integer> tauGrid = samples.stream()
                .map(ResidualSample::tauSec)
                .distinct()
                .sorted()
                .toList();

        int offGrid = (int) samples.stream()
                .filter(sample -> !DESIGN_TAU_GRID.contains(sample.tauSec()))
                .count();

        long rows = service.persistedSampleCount();

        return new Result((int) rows, samples.size(), tauGrid, offGrid,
                service.duplicateSkippedCount(), truth.get(truth.size() - 1).sM(), truth);
    }
}
