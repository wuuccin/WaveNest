package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.storage.entity.CenterlinePointEntity;
import com.waterlogistics.storage.repository.CenterlinePointMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CenterlineLedger} 几何一致性测试（11-06 序号 2 · 杨添爻）。
 *
 * <p>台账灌的是 2026-10-28 放样定稿的赣江航段折线（朝阳—南昌—八一，坐标见
 * {@code 106-演示基准值台账}「已定」行）。本类的断言值全部来自 11-06 清单 §四 的
 * 台账判据——这些数字已经按台账顶点离线复算核对过，任何一处对不上都意味着
 * <b>灌数或几何实现错了</b>，不允许调宽容差让它过去。
 *
 * <p>无库依赖：{@code CenterlinePointMapper} 是接口，用 JDK 动态代理做内存表
 * （与 {@code CargoReadyServiceTest} 同一手法）。
 */
class CenterlineLedgerGeometryTest {

    /** 013_centerline.sql 灌的定稿折线（朝阳 → 南昌 → 八一）。 */
    private static final List<WaterwayGeometry.Vertex> CHART = List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.859, 28.663),
            new WaterwayGeometry.Vertex(115.879, 28.692));

    /** 013 灌的台账自检里程（沿程米），按几何类口径复算（见 SQL 注释），与顶点一一对应。 */
    private static final List<Double> CHART_S_M = List.of(0.0, 2009.9, 5778.9);

    private final List<CenterlinePointEntity> table = new ArrayList<>();

    private CenterlineLedger ledger;

    @BeforeEach
    void setUp() {
        table.clear();
        for (int i = 0; i < CHART.size(); i++) {
            CenterlinePointEntity row = new CenterlinePointEntity();
            row.setChartId("GANJIANG");
            row.setVersion(1);
            row.setSeq(i + 1);
            row.setSM(CHART_S_M.get(i));
            row.setLon(CHART.get(i).lon());
            row.setLat(CHART.get(i).lat());
            table.add(row);
        }
        ledger = new CenterlineLedger(inMemoryMapper(), "GANJIANG");
    }

    private CenterlinePointMapper inMemoryMapper() {
        return (CenterlinePointMapper) Proxy.newProxyInstance(
                CenterlinePointMapper.class.getClassLoader(),
                new Class<?>[]{CenterlinePointMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.copyOf(table);
                    }
                    if ("toString".equals(method.getName())) {
                        return "inMemoryCenterlinePointMapper";
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("台账可载入，且里程自检与 013 灌数一致")
    void load_shouldPassMileageSelfCheck() {
        WaterwayGeometry geometry = ledger.load();
        assertNotNull(geometry, "定稿折线必须能从台账载入");
    }

    @Test
    @DisplayName("判据①：折线全长 5.78 km（代码口径复算 5778.9 m，与 106 台账 5785.5 差 0.11% 系常数口径）")
    void totalLength_shouldMatchChart() {
        assertEquals(5778.9, ledger.load().totalLengthM(), 2.0);
        assertTrue(ledger.load().totalLengthM() > 5_770.0 && ledger.load().totalLengthM() < 5_790.0,
                "全长必须落在 5.78 km 判据的口径容差内");
    }

    @Test
    @DisplayName("判据②：NEST-01（115.848/28.642）对中心线垂距 592 m、沿程 0.00 km")
    void nest01_projection_shouldMatchChart() {
        WaterwayGeometry.Projection p = ledger.load().project(115.848, 28.642);
        assertEquals(592.0, p.crossTrackDistanceM(), 2.0);
        assertEquals(0.0, p.s(), 5.0, "NEST-01 落在朝阳端点外侧，垂足夹到折线首端");
    }

    @Test
    @DisplayName("判据③：NEST-02（115.867/28.698）垂距 1349 m（代码口径 1347.0）、沿程 5.75 km（代码口径 5743.7）")
    void nest02_projection_shouldMatchChart() {
        WaterwayGeometry.Projection p = ledger.load().project(115.867, 28.698);
        assertEquals(1347.0, p.crossTrackDistanceM(), 2.0,
                "与 10-28 放样值 1349 差 0.15%，来自米/度常数口径（106 台账用 111320 m/°）");
        assertEquals(5743.7, p.s(), 5.0);
    }

    @Test
    @DisplayName("判据④：船起点（115.8630/28.6680，11-06 口径）垂距 46 m、沿程 2.69 km（代码口径 2687.6）")
    void shipStart_projection_shouldMatchChart() {
        WaterwayGeometry.Projection p = ledger.load().project(115.8630, 28.6680);
        assertEquals(46.0, p.crossTrackDistanceM(), 2.0);
        assertEquals(2687.6, p.s(), 5.0);
    }

    @Test
    @DisplayName("判据⑤：两巢直线距离 6.50 km（与航段 5.78 km 是两个量）")
    void nestPair_distance_shouldMatchChart() {
        double metersPerDegLon = WaterwayGeometry.METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(28.669));
        double dx = (115.867 - 115.848) * metersPerDegLon;
        double dy = (28.698 - 28.642) * WaterwayGeometry.METERS_PER_DEG_LAT;
        assertEquals(6500.0, Math.hypot(dx, dy), 5.0);
    }

    @Test
    @DisplayName("台账里程灌错时整条拒载（自检列的意义所在）")
    void load_shouldRejectWhenMileageDrifts() {
        table.get(1).setSM(9999.0);
        assertNull(ledger.load(), "s_m 与顶点重算值偏差超容差必须拒载");
    }

    @Test
    @DisplayName("台账无行时回落 null（调用方再落配置串／退化口径）")
    void load_shouldReturnNullWhenChartMissing() {
        table.clear();
        assertNull(ledger.load());
    }

    @Test
    @DisplayName("顶点不足两个时回落 null（折线无法定义方向）")
    void load_shouldReturnNullWhenTooFewVertices() {
        table.remove(table.size() - 1);
        table.remove(table.size() - 1);
        assertNull(ledger.load(), "只剩 1 个顶点，折线无法定义方向");
    }

    @Test
    @DisplayName("换曲线走新版本行：读取器只认最新版本")
    void load_shouldUseLatestVersionOnly() {
        CenterlinePointEntity moved = new CenterlinePointEntity();
        moved.setChartId("GANJIANG");
        moved.setVersion(2);
        moved.setSeq(1);
        moved.setSM(0.0);
        moved.setLon(115.852);
        moved.setLat(28.646);
        CenterlinePointEntity moved2 = new CenterlinePointEntity();
        moved2.setChartId("GANJIANG");
        moved2.setVersion(2);
        moved2.setSeq(2);
        // v2 折线 (115.852,28.646)→(115.8535,28.6505) 按几何类口径复算全长 521.4 m
        moved2.setSM(521.4);
        moved2.setLon(115.8535);
        moved2.setLat(28.6505);
        table.add(moved);
        table.add(moved2);

        WaterwayGeometry geometry = ledger.load();
        assertNotNull(geometry);
        assertEquals(521.4, geometry.totalLengthM(), 2.0, "v2 全长 521.4 m，说明用的是最新版本");
    }

    @Test
    @DisplayName("查询抛错时回落 null 而不是把异常抛给装配（预测不因台账拦停）")
    void load_shouldFallBackWhenQueryThrows() {
        CenterlinePointMapper broken = (CenterlinePointMapper) Proxy.newProxyInstance(
                CenterlinePointMapper.class.getClassLoader(),
                new Class<?>[]{CenterlinePointMapper.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("库断了");
                });
        CenterlineLedger brokenLedger = new CenterlineLedger(broken, "GANJIANG");
        assertNull(brokenLedger.load());
        assertTrue(true);
    }
}
