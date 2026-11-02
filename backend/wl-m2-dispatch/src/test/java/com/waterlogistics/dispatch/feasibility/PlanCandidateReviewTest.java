package com.waterlogistics.dispatch.feasibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.dispatch.candidate.CandidateQueries;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 11-01 评审 MEDIUM.16（硬判据 4）＋ HIGH.6 ＋ MEDIUM.18 的回归。
 *
 * <h3>MEDIUM.16 · 未登记抗风不能反而最宽松</h3>
 * <p>原回落值取 {@code criticalWindMs = 12.0}——那是气象熔断阈值、也是已登记机型里抗风最高的一档。
 * 后果：台账<b>漏填</b>抗风的机型，判得比填了 10.0 的还松，等于「数据缺失换豁免」。
 * <p>本类用同一阵风 11 m/s、三个机型（10.0／未登记／12.0）钉住：
 * 未登记机型必须和 10.0 一样被淘汰，<b>不能</b>像 12.0 那样存活。
 *
 * <h3>HIGH.6 · 告警机不能进候选</h3>
 * <p>原查询只看 {@code status = 空闲}，{@code health_status = 告警} 的机器照样被派出去。
 *
 * <h3>MEDIUM.18 · 机型串 trim</h3>
 * <p>{@code "M30T, M400"}（逗号后带空格）拆出 {@code " M400"}，与 {@code "M400"} 不等，
 * 该机型被静默排除。
 */
class PlanCandidateReviewTest {

    private static final long NOW = System.currentTimeMillis();

    // ───────────── 桩 ─────────────

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
                    if (method.getReturnType() == int.class || method.getReturnType() == long.class) {
                        return 0;
                    }
                    return null;
                });
    }

    /**
     * 把 wrapper 的查询条件摊成字符串（反射取 {@code paramNameValuePairs}）。
     *
     * <p>为什么不用 {@code toString()}：{@code LambdaQueryWrapper} 的 {@code toString()} 只给
     * {@code WHERE (?)} 这种占位形式，读不出「按哪个字段过滤」。条件值在
     * {@code paramNameValuePairs}（MP 3.x 父类字段）里，键形如 {@code MPGENVAL1/2}，
     * 加上 {@code expression}（{@code getSqlSegment}）里带的列名即可判断。
     * 拿不到就返回空串——那样桩会返回全部行，被测的过滤逻辑就测不出来了，
     * 所以本类的断言会明确失败而不是悄悄变绿。
     */
    /**
     * 无人机查询桩。
     *
     * <p><b>为什么按"被测代码的查询语义"过滤，而不是解析 wrapper</b>：单测不起 MyBatis 容器，
     * {@code LambdaQueryWrapper.getSqlSegment()} 需要 lambda 列名缓存，直接调用会抛
     * {@code can not find lambda cache}。而桩若无条件返回全部行，
     * 「告警机被排除」这类断言就永远测不出来（两种实现都能过）。
     * 故这里<b>按被测代码固定的查询口径</b>（{@code status = 空闲} 且
     * {@code health_status = 健康}）过滤——口径变了本类会红，逼着人回来看。
     */
    @SuppressWarnings("unchecked")
    private static DroneMapper droneMapperReturning(List<DroneEntity> all) {
        return mapperStub(DroneMapper.class, args -> all.stream()
                .filter(d -> "空闲".equals(d.getStatus()))
                .filter(d -> "健康".equals(d.getHealthStatus()))
                .toList());
    }

    /**
     * 一台无人机。
     *
     * @param windLimit {@code null} 表示「抗风未登记」（落 {@code NaN}）
     */
    private static DroneEntity drone(String id, String model, Double windLimit) {
        DroneEntity d = new DroneEntity();
        d.setDroneId(id);
        d.setModel(model);
        d.setStatus("空闲");
        d.setHealthStatus("健康");
        d.setCurrentNestId("NEST-01");
        d.setBattery(BigDecimal.valueOf(90));
        d.setMaxPayload(BigDecimal.valueOf(9));
        d.setWindLimitMs(windLimit == null ? null : BigDecimal.valueOf(windLimit));
        d.setCruiseSpeedMs(BigDecimal.valueOf(12));
        return d;
    }

    private static NestEntity nest(String supportedModels) {
        NestEntity n = new NestEntity();
        n.setNestId("NEST-01");
        n.setLon(BigDecimal.valueOf(115.848));
        n.setLat(BigDecimal.valueOf(28.642));
        n.setCapacity(4);
        n.setSupportedModels(supportedModels);
        n.setOwner("demo");
        n.setStatus("可用");
        return n;
    }

    private static OrderTaskEntity order() {
        OrderTaskEntity o = new OrderTaskEntity();
        o.setOrderId("ORD-1101-1");
        o.setShipLon(BigDecimal.valueOf(115.8611));
        o.setShipLat(BigDecimal.valueOf(28.6660));
        o.setGoodsWeight(BigDecimal.valueOf(2.0));
        return o;
    }

    /** 预警 8.0、熔断 12.0（与 {@code 002_sys_param.sql} 已登记值一致）。 */
    private static CandidateFeasibilityPlanner planner(List<DroneEntity> drones, NestEntity nest,
                                                       double windMs) {
        DroneMapper droneMapper = droneMapperReturning(drones);
        NestMapper nestMapper = mapperStub(NestMapper.class, args -> List.of(nest));
        SysParamMapper sysParamMapper = mapperStub(SysParamMapper.class, args -> List.of());

        WeatherSettings settings = new WeatherSettings(60_000L, 8.0, 12.0, 1);
        WeatherSnapshotStore store = new WeatherSnapshotStore(settings);
        store.accept(new WeatherData(115.8611, 28.6660, windMs, 180.0,
                windMs, NOW, NOW, NOW));

        return new CandidateFeasibilityPlanner(droneMapper, nestMapper,
                new CandidateService(), store, settings, new SysParamLoader(sysParamMapper));
    }

    private static CandidateFeasibilityPlanner.CandidateVerdict verdictOf(
            CandidateFeasibilityPlanner.FeasibleSelection selection, String model) {
        return selection.candidates().stream()
                .filter(v -> model.equals(v.model()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("候选里没有机型 " + model + "："
                        + selection.candidates().stream().map(
                                CandidateFeasibilityPlanner.CandidateVerdict::model).toList()));
    }

    // ───────────── MEDIUM.16 · 硬判据 4 ─────────────

    /**
     * 主用例：11 m/s 下三个机型同场——
     * M30T(10.0) 淘汰、M400(12.0) 存活，而「未登记抗风」必须<b>跟 10.0 一起淘汰</b>。
     *
     * <p>旧口径下未登记机型比的是 12.0，会与 M400 一起存活——本条即红。
     */
    @Test
    @DisplayName("硬判据4：11 m/s 下未登记抗风机型不得存活（旧回落 12.0 会放行）")
    void unregisteredWindLimitIsNotTheLoosest() {
        List<DroneEntity> drones = List.of(
                drone("UAV-01", "M30T", 10.0),
                drone("UAV-NR", "M350", null),          // 抗风未登记
                drone("DRN-M400-01", "M400", 12.0));
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                planner(drones, nest("M30T,M350,M400"), 11.0).selectFeasibleWithReasons(order());

        assertFalse(verdictOf(selection, "M30T").feasible(), "M30T（10.0）在 11 m/s 下应淘汰");
        assertTrue(verdictOf(selection, "M400").feasible(), "M400（12.0）在 11 m/s 下应保留");

        CandidateFeasibilityPlanner.CandidateVerdict unregistered = verdictOf(selection, "M350");
        assertFalse(unregistered.feasible(),
                "未登记抗风的机型在 11 m/s 下必须淘汰——旧口径回落 12.0 会把它放行（硬判据 4）");
        assertTrue(unregistered.reasons().stream().anyMatch(r -> r.contains("未登记")),
                "原因应写明抗风未登记，实际：" + unregistered.reasons());

        assertEquals("DRN-M400-01", selection.chosen().droneId(),
                "最终中选的应是唯一可行的 M400");
    }

    /** 回落上限取「已登记最小值」与「预警阈值 8.0」的较小者：本批最小是 10.0，故上限＝8.0。 */
    @Test
    @DisplayName("硬判据4：回落上限取预警阈值 8.0（低于本批已登记最小值 10.0）")
    void fallbackTakesTheSmallerOfWarningAndRegisteredMin() {
        List<DroneEntity> drones = List.of(
                drone("UAV-NR", "M350", null),
                drone("DRN-M400-01", "M400", 12.0));
        // 9.0 > 8.0 回落上限 ⇒ 未登记机型淘汰；M400（12.0）保留
        CandidateFeasibilityPlanner.FeasibleSelection sel9 =
                planner(drones, nest("M350,M400"), 9.0).selectFeasibleWithReasons(order());
        assertFalse(verdictOf(sel9, "M350").feasible(), "9 m/s 已超回落上限 8.0，未登记机型应淘汰");
        assertTrue(verdictOf(sel9, "M400").feasible(), "M400 在 9 m/s 下仍可行");

        // 7.0 < 8.0 ⇒ 未登记机型保留（回落不是「一律拦死」）
        CandidateFeasibilityPlanner.FeasibleSelection sel7 =
                planner(drones, nest("M350,M400"), 7.0).selectFeasibleWithReasons(order());
        assertTrue(verdictOf(sel7, "M350").feasible(),
                "7 m/s 低于回落上限 8.0，未登记机型应保留（回落不得变成无条件拒单）");
    }

    /** 本批已登记最小值比预警阈值更低时，取更严的那个（10.0 批里最小值 6.0 ⇒ 上限 6.0）。 */
    @Test
    @DisplayName("硬判据4：本批已登记最小值低于预警阈值时，回落取该最小值")
    void fallbackTakesRegisteredMinWhenItIsStricter() {
        List<DroneEntity> drones = List.of(
                drone("UAV-NR", "M350", null),
                drone("UAV-01", "M30T", 6.0));
        // 上限应为 6.0：7 m/s 时未登记机型淘汰，已登记的 6.0 也淘汰
        CandidateFeasibilityPlanner.FeasibleSelection sel =
                planner(drones, nest("M350,M30T"), 7.0).selectFeasibleWithReasons(order());
        assertFalse(verdictOf(sel, "M350").feasible(),
                "本批最弱机型抗风 6.0 < 预警 8.0，未登记机型回落上限应取 6.0，7 m/s 下淘汰");
    }

    // ───────────── HIGH.6 · 健康状态过滤 ─────────────

    /**
     * 验证候选查询<b>确实带上了</b> {@code health_status} 条件，且两处入口都走同一份条件。
     *
     * <p>为什么用「读源码」这种笨办法：过滤发生在 MyBatis 的 SQL 生成阶段，单测不起容器就执行不到。
     * 桩侧过滤只能证明「桩会滤」，不能证明「代码会滤」——后者才是 HIGH.6 要的。
     *
     * <p><b>断言口径已升级（逐字审查）</b>：原断言只检查「planner 与 selector 两个文件里都有
     * getHealthStatus」。那能守住这两个文件，却守不住「将来新增第三个候选入口」。
     * 现在条件收敛在 {@link CandidateQueries}，断言改为：
     * <ol>
     *   <li>{@code CandidateQueries} 必须带 {@code getHealthStatus} 且用「健康」白名单；</li>
     *   <li>两个入口<b>不得</b>再自己拼健康过滤（否则又长出第二份口径）——
     *       它们必须各自调用 {@code CandidateQueries}。</li>
     * </ol>
     * 真正的 SQL 效果由张睿哲的集成测试/端到端复跑兜底。
     */
    @Test
    @DisplayName("HIGH.6：查询条件集中在 CandidateQueries，两个候选入口都必须走它")
    void queriesMustFilterByHealthStatus() throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of("src/main/java/com/waterlogistics/dispatch");

        String queries = java.nio.file.Files.readString(
                base.resolve("candidate/CandidateQueries.java"));
        assertTrue(queries.contains("getHealthStatus"),
                "CandidateQueries 的无人机查询必须加健康状态过滤（HIGH.6：告警机不得进候选）");
        assertTrue(queries.contains("健康"),
                "CandidateQueries 应使用「健康」白名单口径");

        record Entry(String path, String label) {
        }
        for (Entry e : List.of(
                new Entry("feasibility/CandidateFeasibilityPlanner.java", "planner.buildInput"),
                new Entry("candidate/CandidateSelector.java", "CandidateSelector.selectBest"))) {
            String src = java.nio.file.Files.readString(base.resolve(e.path()));
            assertTrue(src.contains("CandidateQueries."),
                    e.label() + " 必须走 CandidateQueries 的统一查询口径，不得自行拼条件");
            assertFalse(src.contains("getHealthStatus"),
                    e.label() + " 不应再自行写健康过滤——两处各写一遍正是 HIGH.6 复发的根源");
        }
    }

    /** 非健康机在数据层就被滤掉：桩按被测代码口径过滤后，只有健康机进候选。 */
    @Test
    @DisplayName("HIGH.6：healthStatus=告警 的无人机不得进入候选")
    void unhealthyDroneIsExcluded() {
        DroneEntity alert = drone("UAV-ALERT", "M30T", 12.0);
        alert.setHealthStatus("告警");
        List<DroneEntity> drones = List.of(alert, drone("UAV-01", "M30T", 10.0));

        CandidateFeasibilityPlanner.FeasibleSelection selection =
                planner(drones, nest("M30T"), 5.0).selectFeasibleWithReasons(order());

        assertEquals(1, selection.candidates().size(), "只有健康的那台应进候选");
        assertEquals("UAV-01", selection.candidates().get(0).droneId());
    }

    // ───────────── MEDIUM.18 · 机型串 trim ─────────────

    @Test
    @DisplayName("MEDIUM.18：supportedModels 带空格时仍能匹配机型（不 trim 会静默漏机型）")
    void supportedModelsAreTrimmed() {
        List<DroneEntity> drones = List.of(
                drone("UAV-01", "M30T", 12.0),
                drone("DRN-M400-01", "M400", 12.0));

        CandidateFeasibilityPlanner.FeasibleSelection selection =
                planner(drones, nest("M30T, M400"), 5.0).selectFeasibleWithReasons(order());

        assertEquals(2, selection.candidates().size(),
                "逗号后带空格时两个机型都应匹配成功，实际候选："
                        + selection.candidates().stream()
                                .map(CandidateFeasibilityPlanner.CandidateVerdict::model).toList());
    }

    /** 全空串与 null 语义一致：都表示「未登记、不限制机型」。 */
    @Test
    @DisplayName("MEDIUM.18：supportedModels 为空串/空白时按未登记处理，不放行成 [\"\"] 这样的假机型")
    void blankSupportedModelsMeansUnrestricted() {
        List<DroneEntity> drones = List.of(drone("UAV-01", "M30T", 12.0));
        List<CandidateFeasibilityPlanner.CandidateVerdict> all = new ArrayList<>();
        for (String s : new String[] {null, "", "   ", " , ,"}) {
            all.addAll(planner(drones, nest(s), 5.0).selectFeasibleWithReasons(order()).candidates());
        }
        assertEquals(4, all.size(), "四种空白写法都应视为「不限制机型」，各生成 1 个候选：" + all.size());
    }

    // ───────────── MEDIUM.13 · 补丁：生产主路径（planner）也要拦缺字段 ─────────────

    /**
     * 原修复只落在 {@code CandidateSelector}，但它已无调用点——DispatchOrchestrator 实际走
     * 本类的 {@code selectFeasibleWithReasons}。旧 buildInput 对缺经纬度填 0.0，
     * 取货点被挪到 (0,0)，距离 9000+ km 全被过滤，现场只看到「无可用候选组合，候选 0 条」
     * （10-30 异常告警复验实录），真实原因被埋掉。本条锁定：planner 路径缺失即抛。
     */
    @Test
    @DisplayName("MEDIUM.13：生产主路径 selectFeasibleWithReasons 遇订单缺 ship_lon 必须抛，不填 0 兜底")
    void plannerPathRejectsOrderMissingShipLon() {
        OrderTaskEntity missing = order();
        missing.setShipLon(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> planner(List.of(drone("UAV-01", "M30T", 12.0)), nest("M30T"), 5.0)
                        .selectFeasibleWithReasons(missing));
        assertTrue(ex.getMessage().contains("ship_lon"),
                "异常消息要指明缺的是哪个字段，实际：" + ex.getMessage());

        OrderTaskEntity missingWeight = order();
        missingWeight.setGoodsWeight(null);
        assertThrows(IllegalStateException.class,
                () -> planner(List.of(drone("UAV-01", "M30T", 12.0)), nest("M30T"), 5.0)
                        .selectFeasibleWithReasons(missingWeight),
                "缺 goods_weight 同理必须抛");
    }
}
