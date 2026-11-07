package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.storage.entity.CenterlinePointEntity;
import com.waterlogistics.storage.repository.CenterlinePointMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 中心线台账启动依赖分档测试（11-07 弥补 D-08／D-17）。
 *
 * <p>无库依赖：{@code CenterlinePointMapper} 是接口，用 JDK 动态代理做内存表。
 * 启动检查方法 {@link CenterlineLedger#enforceStartupDependency()} 是包内可见，
 * 纯单测直接调用即可，不必起 Spring 容器。
 */
class CenterlineLedgerStartupDependencyTest {

    private static CenterlinePointMapper mapperReturning(List<CenterlinePointEntity> rows) {
        return (CenterlinePointMapper) Proxy.newProxyInstance(
                CenterlinePointMapper.class.getClassLoader(),
                new Class<?>[]{CenterlinePointMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.copyOf(rows);
                    }
                    if ("toString".equals(method.getName())) {
                        return "stubCenterlinePointMapper";
                    }
                    return null;
                });
    }

    /** 013_centerline.sql 灌的定稿三顶点折线（里程自检列与几何类复算值同源）。 */
    private static List<CenterlinePointEntity> validChart() {
        List<WaterwayGeometry.Vertex> chart = List.of(
                new WaterwayGeometry.Vertex(115.852, 28.646),
                new WaterwayGeometry.Vertex(115.859, 28.663),
                new WaterwayGeometry.Vertex(115.879, 28.692));
        List<Double> mileage = List.of(0.0, 2009.9, 5778.9);
        List<CenterlinePointEntity> rows = new ArrayList<>();
        for (int i = 0; i < chart.size(); i++) {
            CenterlinePointEntity row = new CenterlinePointEntity();
            row.setChartId("GANJIANG");
            row.setVersion(1);
            row.setSeq(i + 1);
            row.setSM(mileage.get(i));
            row.setLon(chart.get(i).lon());
            row.setLat(chart.get(i).lat());
            rows.add(row);
        }
        return rows;
    }

    @Test
    @DisplayName("缺省 required 档缺表：启动失败，且异常文案给可直接执行的灌库命令")
    void requiredMode_shouldFailStartupWhenLedgerMissing() {
        CenterlineLedger ledger = new CenterlineLedger(mapperReturning(List.of()), "GANJIANG",
                CenterlineLedger.MODE_REQUIRED);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                ledger::enforceStartupDependency);

        assertTrue(thrown.getMessage().contains("centerline_point"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("014_centerline_v2.sql"),
                "异常文案必须含可直接执行的灌库命令");
        assertTrue(thrown.getMessage().contains(CenterlineLedger.KEY_LEDGER_MODE),
                "异常文案必须给出显式降级属性键");
    }

    @Test
    @DisplayName("显式 baseline 档缺表：不阻断启动，台账仍回落 null 由配置串兜底")
    void baselineMode_shouldStartWhenLedgerMissing() {
        CenterlineLedger ledger = new CenterlineLedger(mapperReturning(List.of()), "GANJIANG",
                CenterlineLedger.MODE_BASELINE);

        assertDoesNotThrow(ledger::enforceStartupDependency);
        assertEquals(CenterlineLedger.MODE_BASELINE, ledger.mode());
        assertNull(ledger.load(), "降级档不改读取器语义，仍交 PredictionConfig 回落到内置基线");
    }

    @Test
    @DisplayName("非法档位按安全侧回落 required，不得意外放宽成降级档")
    void illegalMode_shouldFallBackToRequired() {
        CenterlineLedger ledger = new CenterlineLedger(mapperReturning(List.of()), "GANJIANG", "auto");

        assertEquals(CenterlineLedger.MODE_REQUIRED, ledger.mode());
        assertThrows(IllegalStateException.class, ledger::enforceStartupDependency);
    }

    @Test
    @DisplayName("required 档台账可载入：启动检查放行，几何对象被缓存复用")
    void requiredMode_shouldPassWhenLedgerLoads() {
        CenterlineLedger ledger = new CenterlineLedger(mapperReturning(validChart()), "GANJIANG",
                CenterlineLedger.MODE_REQUIRED);

        assertDoesNotThrow(ledger::enforceStartupDependency);
        assertNotNull(ledger.load());
    }

    @Test
    @DisplayName("两参构造器默认 required，且不触发启动检查（纯单测路径不受影响）")
    void twoArgConstructor_shouldDefaultToRequiredWithoutStartupCheck() {
        CenterlineLedger ledger = new CenterlineLedger(mapperReturning(List.of()), "GANJIANG");

        assertEquals(CenterlineLedger.MODE_REQUIRED, ledger.mode());
        assertNull(ledger.load(), "两参构造器不经容器，缺表时 load 仍只回落 null");
    }
}
