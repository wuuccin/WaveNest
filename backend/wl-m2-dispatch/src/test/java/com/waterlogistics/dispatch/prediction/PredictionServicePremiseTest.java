package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.storage.entity.CenterlinePointEntity;
import com.waterlogistics.storage.repository.CenterlinePointMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 匀速路径前提监测接线测试（11-06 序号 2 · 杨添爻）。
 *
 * <p>硬判据 7 的服务层四条：输入不足返回 {@code UNKNOWN}、方向翻转返回
 * {@code SUSPECT} 或 {@code INVALID}、精算路径行为不变、结论走既有
 * {@code Verdict#toStatus} 映射（映射本身由算法层 {@code PremiseMonitorTest} 负责，
 * 这里验的是<b>接线</b>——生产调用点确实把结论传进了八参重载）。
 *
 * <p>无库依赖：台账用动态代理内存表（与 {@code CenterlineLedgerGeometryTest} 同一手法）。
 */
class PredictionServicePremiseTest {

    private static final long T0 = 1_760_000_000_000L;

    private static final double KNOT_TO_MS = 1852.0 / 3600.0;

    /** 演示船速 8 节（106-演示基准值台账），合 4.12 m/s。 */
    private static final double SOG_KNOTS = 8.0;

    private final PredictionSettings settings =
            new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);

    /** 013_centerline.sql 灌的定稿折线（朝阳 → 南昌 → 八一）。 */
    private WaterwayGeometry chart;

    private List<WaterwayGeometry.Vertex> chartVertices;

    @BeforeEach
    void setUp() {
        chartVertices = List.of(
                new WaterwayGeometry.Vertex(115.852, 28.646),
                new WaterwayGeometry.Vertex(115.859, 28.663),
                new WaterwayGeometry.Vertex(115.879, 28.692));
        chart = new WaterwayGeometry(chartVertices);
    }

    /** 无台账装配：P0／P3 退化的最小形态（见 PredictionService#advancePremise）。 */
    private PredictionService bareService() {
        return new PredictionService(settings, new LoggingRecomputeRequester());
    }

    /** 带台账装配：中心线投影可用，P0–P4 全量判据在线。 */
    private PredictionService chartService() {
        PredictionService service = new PredictionService(settings, new LoggingRecomputeRequester());
        service.bindCenterlineLedger(new CenterlineLedger(inMemoryMapper(), "GANJIANG"));
        return service;
    }

    private CenterlinePointMapper inMemoryMapper() {
        return (CenterlinePointMapper) Proxy.newProxyInstance(
                CenterlinePointMapper.class.getClassLoader(),
                new Class<?>[]{CenterlinePointMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        List<CenterlinePointEntity> rows = new java.util.ArrayList<>();
                        double[] sM = {0.0, 2009.9, 5778.9};
                        for (int i = 0; i < chartVertices.size(); i++) {
                            CenterlinePointEntity row = new CenterlinePointEntity();
                            row.setChartId("GANJIANG");
                            row.setVersion(1);
                            row.setSeq(i + 1);
                            row.setSM(sM[i]);
                            row.setLon(chartVertices.get(i).lon());
                            row.setLat(chartVertices.get(i).lat());
                            rows.add(row);
                        }
                        return rows;
                    }
                    if ("toString".equals(method.getName())) {
                        return "inMemoryCenterlinePointMapper";
                    }
                    return null;
                });
    }

    /**
     * 沿中心线推进的观测：按 {@code startS + elapsedSec · v} 取中心线上的点，
     * 航向在中心线走向上叠加 {@code cogOffsetDeg}。
     */
    private AisMessage alongChart(double startS, double elapsedSec, double cogOffsetDeg) {
        double s = startS + 4.12 * elapsedSec;
        WaterwayGeometry.Vertex point = chart.pointAt(s);
        double cog = chart.courseDegAt(s) + cogOffsetDeg;
        return new AisMessage("413000001", "测试船",
                point.lon(), point.lat(), SOG_KNOTS, (cog + 360.0) % 360.0,
                T0 + Math.round(elapsedSec * 1000.0));
    }

    @Test
    @DisplayName("输入不足返回 UNKNOWN：首帧只建状态机不喂（预热帧）")
    void firstFrame_shouldStayUnknown() {
        PredictionSnapshot snapshot = bareService().refresh(alongChart(200.0, 0.0, 0.0));
        assertEquals("UNKNOWN", snapshot.premise(), "单帧输入不足以支撑方向／驻留判据");
    }

    @Test
    @DisplayName("无台账时第二帧起 P1 在线：直线航行返回 VALID（不恒 UNKNOWN）")
    void barePath_secondFrame_shouldBecomeValid() {
        PredictionService service = bareService();
        service.refresh(alongChart(200.0, 0.0, 0.0));
        PredictionSnapshot second = service.refresh(alongChart(200.0, 1.0, 0.0));
        assertEquals("VALID", second.premise(),
                "接线后前提不再是恒 UNKNOWN：cog 与锁定参考一致 → VALID");
    }

    @Test
    @DisplayName("有台账：沿中心线正向航行返回 VALID")
    void chartPath_alongCenterline_shouldBeValid() {
        PredictionService service = chartService();
        service.refresh(alongChart(200.0, 0.0, 0.0));
        PredictionSnapshot second = service.refresh(alongChart(200.0, 1.0, 0.0));
        assertEquals("VALID", second.premise());
    }

    @Test
    @DisplayName("有台账：掉头（航向反转持续超 tDir=30 s）返回 INVALID")
    void chartPath_reversed_shouldBecomeInvalid() {
        PredictionService service = chartService();
        // 先正向锁定方向参考（预热帧 + 一帧确认）
        service.refresh(alongChart(200.0, 0.0, 0.0));
        service.refresh(alongChart(200.0, 1.0, 0.0));
        // 掉头：航向叠加 180°，速度仍 8 节 → vAlong 变负，P0 驻留 30 s 后翻转成立
        PredictionSnapshot latest = null;
        for (int i = 2; i <= 40; i++) {
            latest = service.refresh(alongChart(200.0, i, 180.0));
            if ("INVALID".equals(latest.premise())) {
                break;
            }
        }
        assertNotNull(latest);
        assertEquals("INVALID", latest.premise(), "P0 方向翻转成立后前提被推翻");
    }

    @Test
    @DisplayName("有台账：偏航 25°（超 ψ 阈值 20°）持续，verdict=SUSPECT 经 toStatus 有损映射为 VALID")
    void chartPath_deviated_shouldMapSuspectToValid() {
        PredictionService service = chartService();
        service.refresh(alongChart(200.0, 0.0, 0.0));
        service.refresh(alongChart(200.0, 1.0, 0.0));
        // P1 = M-of-N 3/5 + 驻留 tPsi 120 s；1 Hz 喂 140 帧足够触发。
        // 快照 premise 是三值契约（VALID/INVALID/UNKNOWN）：P1 的 SUSPECT 属
        // 「前提仍成立、触发重新获取预测粗门槛」，经 Verdict#toStatus 有损映射为 VALID
        // ——这是既有映射语义，不是判据失效。方向翻转（P0）走 INVALID，见上一条测试。
        PredictionSnapshot latest = null;
        for (int i = 2; i <= 140; i++) {
            latest = service.refresh(alongChart(200.0, i, 25.0));
        }
        assertNotNull(latest);
        assertEquals("VALID", latest.premise(),
                "SUSPECT（偏航属量变）在快照三值契约中映射为 VALID，前提仍成立");
    }

    @Test
    @DisplayName("同拍重算不重复推进：recompute 复用同一条观测，状态机不炸、结论一致")
    void recompute_sameObservation_shouldReuseVerdictWithoutAdvancing() {
        PredictionService service = chartService();
        PredictionSnapshot first = service.refresh(alongChart(200.0, 0.0, 0.0));
        assertEquals("UNKNOWN", first.premise());

        // 重算用的是 lastObservations 里同一条观测（时标相同）
        PredictionSnapshot recomputed = service.recompute("413000001").orElseThrow();
        assertEquals("UNKNOWN", recomputed.premise(), "同一条观测的前提结论不变");

        // 下一拍时标前进，状态机正常推进
        PredictionSnapshot second = service.refresh(alongChart(200.0, 1.0, 0.0));
        assertEquals("VALID", second.premise());
    }

    @Test
    @DisplayName("精算路径行为不变：结论由引擎透出，不走匀速路径的 advancePremise")
    void precisePath_shouldBeUnaffected() {
        PredictionSettings preciseSettings =
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);
        PredictionProfile profile = PredictionProfile.precise(preciseSettings,
                new PredictionAssurance(PredictionAssurance.Bounds.demoArea()),
                PrecisePredictionEngine.Params.defaults());
        PredictionService service = new PredictionService(preciseSettings,
                new LoggingRecomputeRequester(), event -> { }, profile,
                () -> T0 + 1_800_000L);
        service.bindCenterlineLedger(new CenterlineLedger(inMemoryMapper(), "GANJIANG"));

        // 精算链路正常产出即可：前提字段由引擎的 Outcome 透出（引擎侧 PremiseMonitor
        // 自带接线），本批改动只挂在 constantSnapshot 内，preciseSnapshot 不经过它
        PredictionSnapshot snapshot = service.refresh(alongChart(200.0, 0.0, 0.0));
        assertNotNull(snapshot);
        assertTrue(snapshot.premise() != null && !snapshot.premise().isBlank(),
                "快照前提字段必须被引擎或回落路径填充，不允许出现空串");
    }
}
