package com.waterlogistics.dispatch.feasibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.candidate.FeasibilityJudge;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.dispatch.candidate.CandidateService;
import com.waterlogistics.dispatch.weather.WeatherSettings;
import com.waterlogistics.dispatch.weather.WeatherSnapshotStore;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.SysParamMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * E3（10-23 任务清单）：候选淘汰原因透出。
 *
 * <p>判据：候选结果中能看到<b>每个落选机型的原因文本</b>；断言覆盖至少一类淘汰
 * （本类钉死「机型抗风不足」这一类——11 m/s 下 M30T（抗风 10.0）落选、M400（12.0）保留）。
 *
 * <p>口径：不改判定逻辑、不改阈值、不改原因文案——本测试同时验证
 * 原因文本与 {@code FeasibilityJudge} 原文一致（含机型值 10.0）。
 * MyBatis mapper 用 JDK 动态代理做桩（工程无 Mockito，仓库口径是手写桩）。
 */
class CandidateFeasibilityPlannerReasonsTest {

    private static final long NOW = System.currentTimeMillis();

    private static final double WIND_11 = 11.0;

    /** JDK 动态代理桩：selectList 返回给定行，其余方法给空/null。 */
    @SuppressWarnings("unchecked")
    private static <T> T mapperStub(Class<T> type, Function<Object[], Object> selectListResult) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(proxy, args);
                    }
                    if ("selectList".equals(method.getName())) {
                        return selectListResult.apply(args);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class
                            || method.getReturnType() == long.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static DroneEntity drone(String id, String model, double windLimit) {
        DroneEntity d = new DroneEntity();
        d.setDroneId(id);
        d.setModel(model);
        d.setStatus("空闲");
        // 11-01 评审 HIGH.6：候选查询加了健康状态白名单，桩必须给「健康」，
        // 否则这些用例会因「查不到无人机」全挂在「无可飞候选」上（那不是被测逻辑的问题）
        d.setHealthStatus("健康");
        d.setCurrentNestId("NEST-01");
        d.setBattery(BigDecimal.valueOf(90));
        d.setMaxPayload(BigDecimal.valueOf(9));
        d.setWindLimitMs(BigDecimal.valueOf(windLimit));
        d.setCruiseSpeedMs(BigDecimal.valueOf(12));
        return d;
    }

    private static NestEntity nest() {
        NestEntity n = new NestEntity();
        n.setNestId("NEST-01");
        n.setLon(BigDecimal.valueOf(120.0));
        n.setLat(BigDecimal.valueOf(30.0));
        n.setCapacity(4);
        n.setSupportedModels("M30T,M400");
        n.setOwner("demo");
        n.setStatus("可用");
        return n;
    }

    private static OrderTaskEntity order() {
        OrderTaskEntity o = new OrderTaskEntity();
        o.setOrderId("ORD-E3-1");
        o.setShipLon(BigDecimal.valueOf(120.0));
        o.setShipLat(BigDecimal.valueOf(30.0));
        o.setGoodsWeight(BigDecimal.valueOf(2.0));
        return o;
    }

    private static CandidateFeasibilityPlanner planner(double injectedWindMs) {
        DroneMapper droneMapper = mapperStub(DroneMapper.class, args -> List.of(
                drone("UAV-01", "M30T", 10.0),
                drone("DRN-M400-01", "M400", 12.0)));
        NestMapper nestMapper = mapperStub(NestMapper.class, args -> List.of(nest()));
        SysParamMapper sysParamMapper = mapperStub(SysParamMapper.class, args -> List.of());

        WeatherSettings settings = new WeatherSettings(60_000L, 8.0, 12.0, 1);
        WeatherSnapshotStore store = new WeatherSnapshotStore(settings);
        if (injectedWindMs > 0) {
            store.accept(new WeatherData(120.0, 30.0, injectedWindMs, 180.0,
                    injectedWindMs, NOW, NOW, NOW));
        }

        return new CandidateFeasibilityPlanner(droneMapper, nestMapper,
                new CandidateService(), store, settings, new SysParamLoader(sysParamMapper));
    }

    /** 判据主用例：11 m/s 下候选结果里能看到每个落选机型的原因文本（机型抗风不足）。 */
    @Test
    void selectFeasibleWithReasons_shouldExposePerModelRejectionText() {
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                planner(WIND_11).selectFeasibleWithReasons(order());

        assertTrue(selection.found(), "11 m/s 下 M400（12.0）应保留");
        assertEquals("DRN-M400-01", selection.chosen().droneId());

        assertEquals(2, selection.candidates().size(), "两个候选都应有判定条目");
        for (CandidateFeasibilityPlanner.CandidateVerdict v : selection.candidates()) {
            assertNotNull(v.model(), "每个候选都应带机型");
            // 10-29：distKm 从组合原样透传（桩里机巢与取货点同坐标 ⇒ 0.0）
            assertEquals(0.0, v.distKm(), 1e-9, "每个候选都应带机巢到取货点距离（km）");
            if ("M30T".equals(v.model())) {
                assertFalse(v.feasible(), "M30T（抗风 10.0）在 11 m/s 下应落选");
                assertTrue(v.reasons().stream().anyMatch(r ->
                                r.contains("机型抗风不足") && r.contains("10.0")),
                        "落选原因应透出「机型抗风不足」原文（含机型上限 10.0），实际："
                                + v.reasons());
                assertEquals("UAV-01", v.droneId());
                assertEquals("NEST-01", v.nestId());
            } else if ("M400".equals(v.model())) {
                assertTrue(v.feasible(), "M400（抗风 12.0）在 11 m/s 下应保留");
                assertTrue(v.reasons().isEmpty(), "可行候选原因应为空");
            } else {
                throw new AssertionError("意外的机型：" + v.model());
            }
        }
        assertEquals(1, selection.rejectedCount());
    }

    /** 全部落选：不抛异常，found()=false，每个候选的原因都能被看到。 */
    @Test
    void selectFeasibleWithReasons_shouldExposeReasons_whenAllRejected() {
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                planner(13.0).selectFeasibleWithReasons(order());

        assertFalse(selection.found(), "13 m/s 下两机型（10.0/12.0）应全部落选");
        assertEquals(2, selection.rejectedCount());
        for (CandidateFeasibilityPlanner.CandidateVerdict v : selection.candidates()) {
            assertFalse(v.reasons().isEmpty(), "落选候选必须带原因：" + v.model());
            assertTrue(v.reasons().get(0).contains("机型抗风不足"),
                    "原因文案应与 FeasibilityJudge 原文一致：" + v.reasons());
        }
    }

    /** 回归：原 selectFeasible 行为不变（编排器那处调用由组长改，本方法不能被 E3 改坏）。 */
    @Test
    void selectFeasible_shouldKeepOldContract() {
        assertEquals("DRN-M400-01",
                planner(WIND_11).selectFeasible(order()).droneId());
        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> planner(13.0).selectFeasible(order()));
        assertTrue(ex.getMessage().contains("无可飞候选"), "全落选时仍按原口径抛出：" + ex.getMessage());
    }
}
