package com.waterlogistics.dispatch.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.algo.cargo.CargoNestChoice;
import com.waterlogistics.contract.ws.ShipTrajPoint;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CargoNestDecisionService} 单测——钉住「什么时候选巢」与「选不出来时给什么」。
 *
 * <p>无库依赖：机巢与无人机 mapper 用 JDK 动态代理做内存表，参数表用同法做一张可写的
 * 参数表，船位由构造器注入的窄函数给出。
 *
 * <p>为什么重点测「未命中」：选不出巢时链路要退回旧口径而不是拒绝下单，
 * 而退回旧口径必须留痕。若未命中时静默返回空，现场看不出「这单为什么没等货」。
 */
class CargoNestDecisionServiceTest {

    private static final String MMSI = "413000001";
    private static final double SHIP_LON = 115.8611;
    private static final double SHIP_LAT = 28.6660;

    private final List<NestEntity> nestTable = new ArrayList<>();
    private final List<DroneEntity> droneTable = new ArrayList<>();

    /** 机巢查询次数：用于证明开关关闭时不会白查台账。 */
    private int nestQueries;

    private SysParamLoader sysParamLoader;

    @BeforeEach
    void setUp() {
        nestTable.clear();
        droneTable.clear();
        nestQueries = 0;
        sysParamLoader = new SysParamLoader(inMemorySysParamMapper());
    }

    // ───────────── 开关 ─────────────

    @Test
    @DisplayName("决策开关关闭时不选巢，直接给出关闭原因且不查台账")
    void decide_switchOff_skipsSelection() {
        sysParamLoader.put(CargoNestDecisionService.DECISION_ENABLED_KEY, "false", null, null);
        seedNest("NEST-NEAR", 115.848, 28.642, 4);
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.DISABLED, choice.reason());
        assertEquals(0, nestQueries, "开关关闭时不应查机巢台账");
    }

    @Test
    @DisplayName("参数缺失时按开启处理，行为与登记值一致")
    void decide_parameterAbsent_defaultsToEnabled() {
        seedNest("NEST-NEAR", 115.848, 28.642, 4);
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertTrue(choice.hit());
        assertEquals("NEST-NEAR", choice.nestId());
    }

    // ───────────── 命中 ─────────────

    @Test
    @DisplayName("选巢命中：给出离船更近的巢与距离")
    void decide_hit_returnsNearestNest() {
        seedNest("NEST-NEAR", 115.848, 28.642, 4);
        seedNest("NEST-FAR", 115.867, 28.698, 4);
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertTrue(choice.hit());
        assertEquals("NEST-NEAR", choice.nestId());
        assertEquals(CargoNestChoice.Reason.HIT, choice.reason());
        assertTrue(choice.distanceKm() > 0.0, "命中时必须给出距船距离");
    }

    // ───────────── 未命中 ─────────────

    @Test
    @DisplayName("取不到船位时未命中，原因是没有船位")
    void decide_missesWhenShipPositionAbsent() {
        seedNest("NEST-NEAR", 115.848, 28.642, 4);
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service(Optional.empty()).decide(MMSI, 2.0);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_SHIP_POSITION, choice.reason());
    }

    @Test
    @DisplayName("台账没有可用机巢时未命中，原因是没有可用巢")
    void decide_missesWhenNoNestAvailable() {
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_NEST_AVAILABLE, choice.reason());
    }

    @Test
    @DisplayName("机巢停满时未命中，原因是没有空位")
    void decide_missesWhenNestFull() {
        // 容量 1 且已有一机停在巢里（占位口径为 position_type=原巢）→ 剩余空位为 0
        seedNest("NEST-NEAR", 115.848, 28.642, 1);
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_CAPACITY, choice.reason());
    }

    @Test
    @DisplayName("机型不被巢允许时未命中，原因是没有兼容的无人机")
    void decide_missesWhenNoCompatibleDrone() {
        NestEntity nest = seedNest("NEST-NEAR", 115.848, 28.642, 4);
        nest.setSupportedModels("M60L");
        seedDrone("UAV-01", "NEST-NEAR");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    // ───────────── 与候选链的衔接 ─────────────

    /**
     * 选中的货巢必须真的产得出候选组合。
     *
     * <p><b>为什么要有这条</b>：选巢与候选生成隔着装配器，两段各有一份巢与机的过滤口径。
     * 口径一旦漂移，选巢会选出一个「看着有空闲机、实际产不出任何组合」的货巢，
     * 订单从下单那一刻起就没有候选，永远卡在调度中，现场只看到一句
     * 「无可用候选组合（候选 0 个，全部被淘汰）」，极难定位。
     *
     * <p>本用例造的是最典型的漂移场景：货巢离船更近，但同巢那台机在充电，
     * 另一台同机型机停在几十公里外的巢上。按「就近」选中的巢没有可用机，
     * 选巢必须改选服务得到另一座巢。
     */
    @Test
    @DisplayName("选巢与候选链衔接：选中的货巢一定能产出候选组合")
    void decide_selectedNestAlwaysYieldsCombos() {
        // 近巢只允许 M30T：停在近巢的机在充电，唯一空闲的机是远巢的重载 M400，
        // 它不被近巢允许。按「就近」选中的近巢没有可用机，选巢必须改选远巢；
        // 若选巢只看「有没有空闲机」而不看机型与归属巢，选出的巢就产不出任何组合，
        // 订单从下单那一刻起就卡在调度中。
        NestEntity near = seedNest("NEST-NEAR", 115.848, 28.642, 4);
        near.setSupportedModels("M30T");
        NestEntity far = seedNest("NEST-FAR", 115.867, 28.698, 4);
        far.setSupportedModels("M30T,M400");
        seedDrone("UAV-BUSY", "NEST-NEAR").setStatus("充电中");
        seedDrone("UAV-HEAVY", "NEST-FAR").setModel("M400");

        CargoNestChoice choice = service().decide(MMSI, 2.0);

        assertTrue(choice.hit(), "应当选到一座巢");
        assertEquals("NEST-FAR", choice.nestId(),
                "近巢没有可用机（同巢机在充电，唯一空闲机机型不被允许），应当改选远巢");

        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORD-DECIDE-1");
        order.setShipLon(BigDecimal.valueOf(SHIP_LON));
        order.setShipLat(BigDecimal.valueOf(SHIP_LAT));
        order.setGoodsWeight(BigDecimal.valueOf(2.0));
        order.setCargoNestId(choice.nestId());

        CandidateInput input = CandidateInputAssembler.assemble(order, droneTable, nestTable);
        List<CandidateGenerator.CandidateCombo> combos = new CandidateGenerator()
                .generate(input, CandidateGenerator.Params.defaults());

        assertFalse(combos.isEmpty(),
                "选中的货巢 " + choice.nestId() + " 产不出任何候选组合，订单会卡在调度中");
        assertTrue(combos.stream().allMatch(c -> choice.nestId().equals(c.nestId())),
                "组合的装货巢必须恒为选中的货巢");
    }

    // ───────────── 装配 ─────────────

    /** 决策服务：船位固定为测试船位。 */
    private CargoNestDecisionService service() {
        return service(Optional.of(new ShipTrajPoint(0L, SHIP_LON, SHIP_LAT, 0.0, 0.0)));
    }

    /** 决策服务：船位可指定为缺失。 */
    private CargoNestDecisionService service(Optional<ShipTrajPoint> position) {
        return new CargoNestDecisionService(inMemoryNestMapper(), inMemoryDroneMapper(),
                mmsi -> position, sysParamLoader);
    }

    private NestEntity seedNest(String nestId, double lon, double lat, Integer capacity) {
        NestEntity nest = new NestEntity();
        nest.setNestId(nestId);
        nest.setLon(BigDecimal.valueOf(lon));
        nest.setLat(BigDecimal.valueOf(lat));
        nest.setCapacity(capacity);
        nest.setSupportedModels("M30T");
        nest.setStatus("可用");
        nestTable.add(nest);
        return nest;
    }

    private DroneEntity seedDrone(String droneId, String nestId) {
        DroneEntity drone = new DroneEntity();
        drone.setDroneId(droneId);
        drone.setModel("M30T");
        drone.setStatus("空闲");
        drone.setHealthStatus("健康");
        drone.setBattery(BigDecimal.valueOf(90));
        drone.setMaxPayload(BigDecimal.valueOf(6));
        drone.setCurrentNestId(nestId);
        drone.setPositionType("原巢");
        droneTable.add(drone);
        return drone;
    }

    private NestMapper inMemoryNestMapper() {
        return (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        nestQueries++;
                        return List.copyOf(nestTable);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private DroneMapper inMemoryDroneMapper() {
        return (DroneMapper) Proxy.newProxyInstance(
                DroneMapper.class.getClassLoader(), new Class<?>[]{DroneMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.copyOf(droneTable);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private SysParamMapper inMemorySysParamMapper() {
        return (SysParamMapper) Proxy.newProxyInstance(
                SysParamMapper.class.getClassLoader(), new Class<?>[]{SysParamMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.of();
                    }
                    if ("selectById".equals(method.getName())) {
                        return null;
                    }
                    if ("insert".equals(method.getName()) || "updateById".equals(method.getName())) {
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 代理未特判方法的返回值：整型给 0，布尔给 false，其余给 null。 */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == int.class) {
            return 0;
        }
        return returnType == boolean.class ? Boolean.FALSE : null;
    }
}
