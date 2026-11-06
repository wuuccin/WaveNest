package com.waterlogistics.dispatch.prediction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.storage.entity.CenterlinePointEntity;
import com.waterlogistics.storage.repository.CenterlinePointMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h3>失败语义</h3>
 * <p>台账是参考线的「有则更好」来源，不是预测成立的前提：表不存在、台账号无行、
 * 顶点不足两个、里程自检不过、查询抛错——一律记 warn 并返回 {@code null}，
 * 由 {@link PredictionConfig} 回落到配置串，再回落实测窗口建线的退化口径。
 * <b>只告警一次</b>：台账缺失是装配期状态，会持续存在于每一拍，逐拍 warn 会刷满日志
 * （与 {@code PredictionService.preciseEngineMissingWarned} 同一考虑）。
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

    private final CenterlinePointMapper mapper;

    private final String chartId;

    private volatile boolean failureWarned;

    /** 装配期读一次后的缓存：台账在演示期不变，运行期复用同一几何对象。 */
    private volatile WaterwayGeometry cached;

    public CenterlineLedger(CenterlinePointMapper mapper,
            @Value("${wavenest.m2.prediction.centerline-chart:GANJIANG}") String chartId) {
        this.mapper = mapper;
        this.chartId = chartId == null || chartId.isBlank() ? DEFAULT_CHART_ID : chartId.trim();
    }

    /** 本读取器服务的台账号。 */
    public String chartId() {
        return chartId;
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
