package com.waterlogistics.dispatch.prediction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.storage.entity.CenterlinePointEntity;
import com.waterlogistics.storage.repository.CenterlinePointMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 航道中心线台账读取器（11-06 序号 2 · 杨添爻）。
 *
 * <p>把 {@code centerline_point} 折线点表读成算法层的 {@link WaterwayGeometry}，
 * 供精算引擎作参考线（取代「本船最近实测轨迹 ＋ 尾端直线延伸」的退化口径）。
 *
 * <h3>口径裁定（11-06 清单 §六）</h3>
 * <ul>
 *   <li><b>C-01 表结构</b>：折线点表（台账号、版本、顶点序号、沿程米、经纬度），
 *       拓扑边表留后续；换曲线走新版本行，不改代码。</li>
 *   <li><b>里程自检</b>：表里的 {@code s_m} 是<b>自检列</b>——本类按顶点序列重建几何后
 *       用 {@link WaterwayGeometry} 自己的平面近似重算累计弧长，与台账逐点比对，
 *       偏差超过 {@link #S_M_TOLERANCE_M} 视为台账灌错，整条拒载（回落配置串／退化口径）。
 *       构造几何<b>只用顶点序列</b>，不用台账里程——两套里程算法并存时以几何类为准，
 *       台账列只用于发现灌数错误。</li>
 *   <li><b>C-02 参考线取值</b>在 {@link PrecisePredictionEngine} 侧落：船位投影到中心线
 *       最近点 ＋ 前方一段，不含尾端直线延伸；投影用平面近似（赣江段尺度下误差可忽略）。</li>
 * </ul>
 *
 * <h3>失败语义与启动依赖（11-07 弥补 D-08／D-17）</h3>
 * <p>{@link #load()} 本身仍是「有则更好」的读取：表不存在、台账号无行、顶点不足两个、
 * 里程自检不过、查询抛错——一律记 warn 并返回 {@code null}，由 {@link PredictionConfig}
 * 回落到配置串，再回落实测窗口建线的退化口径。<b>只告警一次</b>：台账缺失是装配期状态，
 * 会持续存在于每一拍，逐拍 warn 会刷满日志。
 *
 * <p><b>但「静默降级」不再被允许发生在启动期而不留痕</b>：{@link #enforceStartupDependency()}
 * 在 Bean 初始化时读一次台账，按 {@code wavenest.m2.prediction.centerline-ledger-mode} 分档：
 * <ul>
 *   <li>{@code required}（缺省，生产唯一许可档）：载入失败直接抛
 *       {@link IllegalStateException}，异常文案给出可直接执行的灌库命令，
 *       应用启动失败——硬判据 6 的「运行时闭环」因此有失败信号；</li>
 *   <li>{@code baseline}（<b>显式</b>降级，仅测试与本地联调）：打 WARN 并改用
 *       {@code wavenest.m2.prediction.centerline} 配置串作内置基线，不阻断启动。</li>
 * </ul>
 * 降级档必须显式写出，不能靠「库里恰好没表」隐式触发；非法取值按安全侧回落 {@code required}。
 * 纯单测用两参构造器直接 {@code new} 本类，不经过容器、不触发 {@code @PostConstruct}，
 * 启动检查对纯单测路径无影响。
 *
 * <p>读取发生在<b>装配期一次</b>（引擎构造时传入，运行期不再查表）：中心线在演示期不变，
 * 逐拍查表只会拖慢预测链路；换版本走重启，语义与「换曲线是运维事件」一致。
 */
@Component
public class CenterlineLedger {

    private static final Logger log = LoggerFactory.getLogger(CenterlineLedger.class);

    /** 里程自检容差，m：平面近似在赣江 5.8 km 尺度上的舍入误差远小于此。 */
    static final double S_M_TOLERANCE_M = 2.0;

    /** 缺省台账号（013_centerline.sql 灌的赣江定稿航段）。 */
    static final String DEFAULT_CHART_ID = "GANJIANG";

    /** 启动依赖档：强依赖，载入失败即启动失败（缺省、生产唯一许可档）。 */
    static final String MODE_REQUIRED = "required";

    /** 启动依赖档：显式降级到内置基线并打 WARN，仅测试与本地联调。 */
    static final String MODE_BASELINE = "baseline";

    /** 启动依赖档属性键。 */
    static final String KEY_LEDGER_MODE = "wavenest.m2.prediction.centerline-ledger-mode";

    /** 缺表时异常文案里的可直接执行灌库命令。 */
    static final String HYDRATE_COMMAND =
            "docker compose -f deploy/docker-compose.yml exec -T mysql "
                    + "mysql -uroot -pwavenest wavenest < deploy/sql/014_centerline_v2.sql";

    private final CenterlinePointMapper mapper;

    private final String chartId;

    private final String mode;

    private volatile boolean failureWarned;

    /** 装配期读一次后的缓存：台账在演示期不变，运行期复用同一几何对象。 */
    private volatile WaterwayGeometry cached;

    @Autowired
    public CenterlineLedger(CenterlinePointMapper mapper,
            @Value("${wavenest.m2.prediction.centerline-chart:GANJIANG}") String chartId,
            @Value("${wavenest.m2.prediction.centerline-ledger-mode:required}") String mode) {
        this.mapper = mapper;
        this.chartId = chartId == null || chartId.isBlank() ? DEFAULT_CHART_ID : chartId.trim();
        String normalized = mode == null ? MODE_REQUIRED : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_REQUIRED.equals(normalized) && !MODE_BASELINE.equals(normalized)) {
            log.warn("中心线台账启动依赖档取值非法（{}），已按安全侧回落 {}；合法取值：{}／{}",
                    mode, MODE_REQUIRED, MODE_REQUIRED, MODE_BASELINE);
            normalized = MODE_REQUIRED;
        }
        this.mode = normalized;
    }

    /**
     * 纯单测兼容构造器：默认强依赖档，且不经容器、不触发启动检查。
     *
     * <p>本构造器不覆盖启动依赖语义；启动期行为由容器路径的
     * {@link #enforceStartupDependency()} 负责。
     */
    public CenterlineLedger(CenterlinePointMapper mapper, String chartId) {
        this(mapper, chartId, MODE_REQUIRED);
    }

    /** 本读取器服务的台账号。 */
    public String chartId() {
        return chartId;
    }

    /** 当前生效的启动依赖档（{@code required} 或 {@code baseline}）。 */
    public String mode() {
        return mode;
    }

    /**
     * 启动期强依赖检查（D-08／D-17）。
     *
     * <p>容器装配时执行一次：台账载入成功即放行；载入失败按档位处置。
     * 纯单测用两参构造器直接实例化，不经过本方法。
     */
    @PostConstruct
    void enforceStartupDependency() {
        if (load() != null) {
            return;
        }
        if (MODE_BASELINE.equals(mode)) {
            log.warn("中心线台账不可用，已按 {} = {} 显式降级启动：参考线改用 "
                            + "wavenest.m2.prediction.centerline 内置基线（配置串为空时退化为逐船自建）。"
                            + "本档仅供测试与本地联调，生产环境不得放宽。chart={}",
                    KEY_LEDGER_MODE, MODE_BASELINE, chartId);
            return;
        }
        throw new IllegalStateException(
                "中心线台账强依赖未满足：chart=" + chartId
                        + " 未能从 centerline_point 载入可用折线（表缺失、无行、顶点不足或里程自检不过）。"
                        + "请先灌库：" + HYDRATE_COMMAND
                        + "（空数据卷首次启动会自动执行 deploy/sql 下全部脚本）。"
                        + "仅测试与本地联调可显式降级："
                        + "-D" + KEY_LEDGER_MODE + "=" + MODE_BASELINE);
    }

    /**
     * 读台账并构造中心线几何。
     *
     * <p>首次调用查表并缓存，此后复用同一几何对象——台账在运行期不变
     * （换曲线走新版本行并重启），逐拍查表只会拖慢预测链路。
     *
     * @return 几何对象；台账为空、灌错或查询失败时为 {@code null}（调用方回落）
     */
    public WaterwayGeometry load() {
        WaterwayGeometry hit = cached;
        if (hit != null) {
            return hit;
        }
        try {
            WaterwayGeometry geometry = loadOnce();
            if (geometry != null) {
                cached = geometry;
            }
            return geometry;
        } catch (RuntimeException e) {
            warnOnce("中心线台账读取失败，参考线回落配置串／退化口径：chart={}", chartId, e);
            return null;
        }
    }

    private WaterwayGeometry loadOnce() {
        // ① 取该台账号全部行（换曲线走新版本行，读取器只认最新版本）
        //    用字符串列名而不是 LambdaQueryWrapper：方法引用的 lambda 解析依赖
        //    MyBatis-Plus 的 TableInfo 缓存，纯单测（无容器）里会抛
        //    「can not find lambda cache」，台账会被误判为读取失败而整体回落
        List<CenterlinePointEntity> rows = mapper.selectList(
                new QueryWrapper<CenterlinePointEntity>().eq("chart_id", chartId));
        if (rows == null || rows.isEmpty()) {
            warnOnce("中心线台账无行，参考线回落配置串／退化口径：chart={}", chartId);
            return null;
        }
        int latestVersion = rows.stream()
                .mapToInt(CenterlinePointEntity::getVersion)
                .max()
                .orElse(0);

        // ② 按 seq 排序取该版本全部顶点
        List<CenterlinePointEntity> points = rows.stream()
                .filter(row -> row.getVersion() == latestVersion)
                .sorted(Comparator.comparingInt(CenterlinePointEntity::getSeq))
                .toList();
        if (points.size() < 2) {
            warnOnce("中心线台账顶点不足两个（version={}，{} 个），参考线回落配置串／退化口径",
                    latestVersion, points.size());
            return null;
        }

        // ③ 顶点序列构造几何；里程自检与台账 s_m 列逐点比对
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>(points.size());
        for (CenterlinePointEntity row : points) {
            vertices.add(new WaterwayGeometry.Vertex(row.getLon(), row.getLat()));
        }
        WaterwayGeometry geometry = new WaterwayGeometry(vertices);

        double[] cumulative = geometry.segmentBoundariesM();
        for (int i = 0; i < points.size(); i++) {
            Double ledgerS = points.get(i).getSM();
            if (ledgerS == null) {
                continue;
            }
            double drift = Math.abs(cumulative[i] - ledgerS);
            if (drift > S_M_TOLERANCE_M) {
                log.warn("中心线台账里程自检不过（seq={} 台账={} m 重算={} m 偏差={} m），"
                                + "参考线回落配置串／退化口径：chart={}",
                        points.get(i).getSeq(), ledgerS, cumulative[i], drift, chartId);
                return null;
            }
        }

        log.info("中心线台账已载入：chart={} version={} 顶点={} 全长={} m",
                chartId, latestVersion, points.size(), geometry.totalLengthM());
        return geometry;
    }

    /** 同类告警只打一次：缺失是装配期状态，逐拍 warn 会刷满日志。 */
    private void warnOnce(String format, Object... args) {
        if (failureWarned) {
            return;
        }
        failureWarned = true;
        log.warn(format, args);
    }
}
