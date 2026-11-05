package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.storage.param.SysParamLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预测参数的装配（M2 · 卢肄文）——<b>阈值一律读 {@code sys_param}</b> 的落地处。
 *
 * <p>依据《技术栈与运行环境》§六 第 3 条「阈值不硬编码」与《10-16 当日任务速览》§四
 * 「阈值一律读 {@code sys_param}，不确定就报组长<b>不臆造</b>」。
 *
 * <h3>键名出处（已逐个核对 {@code deploy/sql/002_sys_param.sql}，不得改名）</h3>
 * <table border="1">
 *   <tr><th>sys_param 键</th><th>值</th><th>含义</th><th>映射到</th></tr>
 *   <tr><td>{@code sigmaPosM}</td><td>20</td><td>AIS 位置误差</td><td>σ∥ 初值</td></tr>
 *   <tr><td>{@code sigmaEM}</td><td>15</td><td>横向稳态标准差</td><td>σ⊥ 初值</td></tr>
 *   <tr><td>{@code alphaAlongMs}</td><td>0.10</td><td>纵向模型误差率</td><td>σ∥ 增长率</td></tr>
 *   <tr><td>{@code alphaCrossMs}</td><td>0.03</td><td>横向模型误差率</td><td>σ⊥ 增长率</td></tr>
 *   <tr><td>{@code horizonSec}</td><td>1800</td><td>精算时域</td><td>预测时长</td></tr>
 * </table>
 *
 * <p><b>★ 两个键在 {@code sys_param} 中尚不存在</b>：{@code stepSec}（点间隔）与
 * {@code validitySec}（有效期）。按纪律<b>不臆造键名</b>，此二者以本类常量作缺省，
 * 待《37》§5.14.1 登记后再改为读表——届时只改本类一处。
 *
 * <p>本类只负责「取值 → 组装」，不参与任何计算；注入 {@link SysParamLoader} 是
 * {@code m2 → wl-storage} 的正常依赖（《包结构设计》§二）。
 */
@Configuration
public class PredictionConfig {

    private static final Logger log = LoggerFactory.getLogger(PredictionConfig.class);

    /**
     * 实现开关（10-23 F4）：{@code precise}（缺省）或 {@code constant}。
     *
     * <p><b>回退口径</b>：改这一行 → 重启后端，即回到匀速版；预估一分钟内可完成，
     * 满足清单「五分钟内切回」的硬要求。
     */
    static final String KEY_MODE = "wavenest.m2.prediction.mode";
    /** 沿航道 σ 初值（AIS 位置误差）。 */
    static final String KEY_SIGMA_POS_M = "sigmaPosM";

    /** 横向 σ 初值（横向稳态标准差）。 */
    static final String KEY_SIGMA_E_M = "sigmaEM";

    /** 沿航道 σ 增长率（纵向模型误差率）。 */
    static final String KEY_ALPHA_ALONG_MS = "alphaAlongMs";

    /** 横向 σ 增长率（横向模型误差率）。 */
    static final String KEY_ALPHA_CROSS_MS = "alphaCrossMs";

    /** 预测时域（精算时域）。 */
    static final String KEY_HORIZON_SEC = "horizonSec";

    /** 分段限速配置键（C7 裁定，见 77 §7）：列表项 {@code {fromS, toS, speedMs}}。 */
    static final String KEY_SEGMENT_SPEED_LIMITS = "wavenest.m2.prediction.segment-speed-limits";

    /** 缺省：预测点间隔，s —— {@code sys_param} 尚无此键（不臆造）。 */
    static final long DEFAULT_STEP_SEC = 60L;

    /** 缺省：快照有效期，s —— {@code sys_param} 尚无此键（不臆造）。 */
    static final long DEFAULT_VALIDITY_SEC = 900L;

    /**
     * 组装预测参数：五个键读 {@code sys_param}，两个未登记键用缺省。
     *
     * <p>{@code SysParamLoader} 的 {@code getDouble/getInt} 在键缺失或解析失败时返回传入的
     * 默认值，故此处无需自行判空。
     */
    @Bean
    public PredictionSettings predictionSettings(SysParamLoader sysParamLoader) {
        return new PredictionSettings(
                positive(sysParamLoader, KEY_SIGMA_POS_M, 20.0, "沿航道 σ 初值"),
                positive(sysParamLoader, KEY_ALPHA_ALONG_MS, 0.10, "纵向模型误差率"),
                positive(sysParamLoader, KEY_SIGMA_E_M, 15.0, "横向稳态标准差"),
                positive(sysParamLoader, KEY_ALPHA_CROSS_MS, 0.03, "横向模型误差率"),
                positiveInt(sysParamLoader, KEY_HORIZON_SEC, 1800, "精算时域"),
                DEFAULT_STEP_SEC,
                DEFAULT_VALIDITY_SEC);
    }

    /**
     * 读参数并校验：取值非有限数或不为正时回落缺省值并记 warn。
     *
     * <p><b>为什么必须校验</b>：{@code sys_param.param_value} 存的是字符串，
     * {@code Double.parseDouble("NaN")} 会正常返回 NaN（不抛异常），坏值会一路进滤波与断言，
     * 表现为「预测结果静默失真」而不是启动失败——这类故障比直接报错更难查。
     *
     * @param loader       参数读取器
     * @param key          参数键
     * @param defaultValue 缺省值（键缺失或解析失败时由读取器返回）
     * @param label        参数中文名，仅用于日志
     * @return 通过校验的取值；不通过时返回缺省值
     */
    private static double positive(SysParamLoader loader, String key, double defaultValue, String label) {
        double value = loader.getDouble(key, defaultValue);
        if (!Double.isFinite(value) || value <= 0.0) {
            log.warn("预测参数取值非法，已回落缺省：{}（key={}）实际={} 缺省={}",
                    label, key, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /** 同 {@link #positive}，用于整数参数（预测时域秒数须为正整数）。 */
    private static int positiveInt(SysParamLoader loader, String key, int defaultValue, String label) {
        int value = loader.getInt(key, defaultValue);
        if (value <= 0) {
            log.warn("预测参数取值非法，已回落缺省：{}（key={}）实际={} 缺省={}",
                    label, key, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * 输出区间断言网（F4 第 1 步）。
     *
     * <p><b>正式取值已定稿</b>：T2-6（10-28）把框收窄到《73-赣江场景说明》§3.1 的服务航段
     * （朝阳大桥 {@code 28.646/115.852} → 八一大桥 {@code 28.692/115.879}，全长 5.78 km）
     * 加航向侧一个预测时域的前伸，落点是 {@code application.yml} 的
     * {@code wavenest.m2.prediction.bbox.*}（{@code 115.857–115.921 × 28.660–28.767}，
     * 按定稿船起点 {@code 115.8611/28.6660}（10-29 序号 2 沿程 2.40 km）实测包络 ＋ 0.5 km 缓冲定，
     * 包络取航段方位角 {@code 27.24°} 与仿真器现值 {@code 31.2°} 两条航向的并集）。
     * 四个值的推导与实测包络见 {@code PredictionBboxNanchangSegmentTest} 的类注释，
     * 该类有「配置与常量同值」的强制守卫，改一处不改另一处会红。
     *
     * <p>下面四个 {@code :默认值} 只是<b>兜底</b>（配置缺失时才生效），取的是
     * 「仿真域 ∪ 赣江航段」的宽框 {@link PredictionAssurance.Bounds#demoArea()}：
     * 早前 M1 仿真器发 {@code 114.30E／30.60N}、赣江在 {@code 115.5–116.5E／28.3–29.1N}，
     * 只框赣江会让联调每帧被拦。M1 已切南昌段，兜底值实际上不会再被触发；
     * 但保留宽兜底是故意的——配置丢了也不至于把整条预测链路拦成匀速版而无人察觉。
     *
     * @param minLon 西界
     * @param minLat 南界
     * @param maxLon 东界
     * @param maxLat 北界
     * @return 断言网
     */
    @Bean
    public PredictionAssurance predictionAssurance(
            @Value("${wavenest.m2.prediction.bbox.min-lon:114.00}") double minLon,
            @Value("${wavenest.m2.prediction.bbox.min-lat:28.30}") double minLat,
            @Value("${wavenest.m2.prediction.bbox.max-lon:116.50}") double maxLon,
            @Value("${wavenest.m2.prediction.bbox.max-lat:30.90}") double maxLat) {
        return new PredictionAssurance(
                new PredictionAssurance.Bounds(minLon, maxLon, minLat, maxLat));
    }

    /**
     * 预测实现画像（模式 ＋ 断言网 ＋ 精算引擎），F4 的装配落点。
     *
     * <p>引擎的两个滤波器参数（{@code q}、{@code thetaMaxDeg} 等）在 {@code sys_param} 中
     * <b>均未登记</b>，按纪律<b>不臆造键名</b>，一律用可覆盖属性承载，缺省取
     * {@code 80} 附录 B 的建议初值；登记后把对应行改成 {@code sysParamLoader.getDouble(...)} 即可，
     * 只改本方法一处。
     *
     * @param settings 预测参数（σ 初值／α／时域／步长，已读 {@code sys_param}）
     * @param assurance 断言网
     * @param sysParamLoader 参数加载器（读 {@code predAlongSpeedBiasMs}，77 §8 G1）
     * @param modeCode 实现开关取值
     * @param laneHalfWidthM 航道半宽，m
     * @param lTolM 纵向容忍上限，m
     * @param wTolM 横向容忍上限，m
     * @param refColdStartSec 冷启动参考窗，s
     * @param q 过程噪声谱密度
     * @param thetaMaxDeg 观测夹角截断，度
     * @param vMaxCraftMs 船速限幅，m/s
     * @param initialVelocityStdMs 初始化纵向速度标准差，m/s
     * @param tauESec 横向回复时间常数，s
     * @return 画像
     */
    @Bean
    public PredictionProfile predictionProfile(PredictionSettings settings,
            PredictionAssurance assurance,
            SysParamLoader sysParamLoader,
            @Value("${wavenest.m2.prediction.mode:precise}") String modeCode,
            @Value("${wavenest.m2.prediction.lane-half-width-m:50}") double laneHalfWidthM,
            @Value("${wavenest.m2.prediction.l-tol-m:180}") double lTolM,
            @Value("${wavenest.m2.prediction.w-tol-m:40}") double wTolM,
            @Value("${wavenest.m2.prediction.ref-cold-start-sec:300}") double refColdStartSec,
            @Value("${wavenest.m2.prediction.q:1.0e-6}") double q,
            @Value("${wavenest.m2.prediction.theta-max-deg:75}") double thetaMaxDeg,
            @Value("${wavenest.m2.prediction.v-max-craft-ms:6}") double vMaxCraftMs,
            @Value("${wavenest.m2.prediction.initial-velocity-std-ms:0.5}") double initialVelocityStdMs,
            @Value("${wavenest.m2.prediction.tau-e-sec:120}") double tauESec,
            @Value("${wavenest.m2.prediction.centerline:}") String centerlineSpec,
            SpeedLimitLookup speedLimit) {

        PredictionMode mode = PredictionMode.fromCode(modeCode);
        if (!mode.code().equalsIgnoreCase(String.valueOf(modeCode).trim())) {
            log.warn("预测实现开关取值无法识别（{}），已回落匀速版；合法取值：precise／constant", modeCode);
        }

        // 匀速版先返回：精算版的参数在 CONSTANT 模式下用不到，
        // 坏配置（如 q=0、theta-max=90）不该把回退模式一起拦停。
        if (mode == PredictionMode.CONSTANT) {
            log.info("预测层运行在匀速版（回退姿态）：{} = constant", KEY_MODE);
            return new PredictionProfile(PredictionMode.CONSTANT, assurance, null);
        }

        PrecisePredictionEngine.Params params = new PrecisePredictionEngine.Params(
                laneHalfWidthM, lTolM, wTolM, refColdStartSec, q, thetaMaxDeg,
                vMaxCraftMs, initialVelocityStdMs, tauESec);

        log.info("预测层运行在精算版：观测滤波 ＋ 曲线坐标 ＋ 协方差传播 ＋ 横向 OU（{} = {}）",
                KEY_MODE, mode.code());
        return PredictionProfile.precise(settings, assurance, params,
                parseCenterline(centerlineSpec), speedLimit, alongSpeedBiasSupplier(sysParamLoader));
    }

    /**
     * 沿航道速度偏置的取值口（77 §8 G1）。
     *
     * <p><b>为什么返回 {@code DoubleSupplier} 而不是当场读出的 {@code double}</b>：这个键由
     * {@link PredictionCalibrationService} 回写（库 ＋ 缓存双写），而引擎是常驻单例。启动时读一次
     * 会把偏置冻结在那一刻，后续回写永不生效——「有定义、有种子、无消费点」的断链会以另一种形式
     * 复现，且这次连日志都不会报。每次预测取值才能让回写即时生效。
     *
     * <p><b>为什么读失败就返回 0 而不是抛</b>：{@code SysParamLoader.getDouble} 在键缺失或解析
     * 失败时返回入参默认值。偏置是修正量，缺失等价于「不修正」，预测退化为现状；
     * 让它抛异常会把整条预测链路拦停，代价远大于收益。
     *
     * @param sysParamLoader 参数加载器
     * @return 取值口，恒不返回 {@code null}
     */
    private static DoubleSupplier alongSpeedBiasSupplier(SysParamLoader sysParamLoader) {
        return () -> sysParamLoader.getDouble(
                PredictionCalibrationService.KEY_ALONG_SPEED_BIAS, 0.0);
    }

    /**
     * 残差样本源（11-03 D1）：把精算引擎的残差出口暴露成容器里唯一的
     * {@link ResidualSampleSource}，供 {@link PredictionCalibrationService} 按可选依赖注入。
     *
     * <p><b>为什么要单独开这个 Bean</b>：引擎实例住在 {@link PredictionProfile} 里
     * （画像整体装配，见该类注释），本身不是容器 Bean；而校准服务只认
     * {@code ObjectProvider<ResidualSampleSource>}。不暴露则容器里永远没有实现，
     * 校准链路会一直退化为「不可用」——那不是 bug 报告，是静默失效，最难发现。
     *
     * <p><b>匀速版为什么返回空实现而不是 {@code null}</b>：{@code @Bean} 方法返回
     * {@code null} 会被注册成 NullBean，{@code ObjectProvider.getIfAvailable()} 拿到的
     * 是 {@code null} 还是抛异常取决于 Spring 版本与注入点，属于「靠实现细节吃饭」。
     * 给一个恒空的实现，语义是明确的「匀速版没有精算状态，也就没有残差可算」。
     *
     * @param profile 预测画像（含引擎）
     * @return 残差样本源
     */
    @Bean
    public ResidualSampleSource residualSampleSource(PredictionProfile profile) {
        PrecisePredictionEngine engine = profile.engine();
        if (engine != null) {
            return engine;
        }
        return mmsi -> List.of();
    }

    /**
     * 分段限速查询（11-03 M4 · 吴可欣）：把配置键
     * {@code wavenest.m2.prediction.segment-speed-limits} 解析成 {@link SpeedLimitLookup}，
     * 暴露为容器里唯一的限速查询 Bean。
     *
     * <p><b>装配边界</b>：本方法只负责「解析配置 → 产出查询」，<b>不</b>把查询接进
     * {@link PrecisePredictionEngine}（引擎归卢肄文，见《111-11-03 任务计划》§5.1）。
     * 引擎侧接入时在 {@code predictionProfile(...)} 里引用本 Bean 并透传给引擎即可，
     * 只改装配一处。
     *
     * <p><b>配置空或非法时回落「恒不限速」</b>（等价 {@link SpeedLimitLookup#unbounded()}）：
     * 预测链路退化为现状，不会因一条配置写坏把整条链路拦停。
     *
     * @param spec 配置串，格式 {@code "fromS,toS,speedMs;..."}
     * @return 分段限速查询；空或非法时为恒不限速
     */
    @Bean
    public SpeedLimitLookup speedLimitLookup(
            @Value("${wavenest.m2.prediction.segment-speed-limits:}") String spec) {
        if (spec == null || spec.isBlank()) {
            log.info("分段限速未配置（{} 为空），预测链路沿用不限速口径", KEY_SEGMENT_SPEED_LIMITS);
        }
        return CenterlineSpeedLimitLookup.parse(spec);
    }

    /**
     * 解析航道中心线配置：分号分隔顶点，每个顶点写 {@code lon,lat}。
     *
     * <p>配置为空或顶点不足两个时返回 {@code null}，引擎回落到实测窗口建参考线的退化口径。
     *
     * @param spec 中心线配置串
     * @return 参考折线，缺失时为空
     */
    private static WaterwayGeometry parseCenterline(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>();
        for (String pair : spec.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("中心线点格式应为 lon,lat：" + trimmed);
            }
            vertices.add(new WaterwayGeometry.Vertex(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim())));
        }
        return vertices.size() < 2 ? null : new WaterwayGeometry(vertices);
    }
}
