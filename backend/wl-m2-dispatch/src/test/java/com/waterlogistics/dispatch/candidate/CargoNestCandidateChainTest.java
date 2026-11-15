package com.waterlogistics.dispatch.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 货巢决策与候选生成的衔接测试——用演示台账的真实数据走一遍「系统选定货巢 → 生成候选」。
 *
 * <p><b>为什么要有这个类</b>：选巢在算法包 {@code CargoNestSelector} 里判，
 * 候选生成在算法包 {@code CandidateGenerator} 里判，两段各有一份巢与机的过滤口径。
 * 两段之间隔着装配器 {@code CandidateInputAssembler}：它把订单上的货巢翻译成货点坐标，
 * 再把台账翻译成巢视图与机视图。任何一段口径与选巢不一致，
 * 现场表现都是同一句「无可用候选组合」，极难定位。
 *
 * <p>因此这里用演示台账的真实数据（两座机巢、四架无人机）把三段串起来跑，
 * 断言「选中货巢之后，候选链必须真的能产出组合」。
 */
class CargoNestCandidateChainTest {

    /** 演示台账：NEST-01 在南昌赣江演示航段南端，允许 M30T 与 M400，容量四。 */
    private static final String NEST_01 = "NEST-01";
    private static final double NEST_01_LON = 115.848;
    private static final double NEST_01_LAT = 28.642;

    /** 演示台账：NEST-02 在航段北端，允许 M30T 与 M3T，容量四。 */
    private static final String NEST_02 = "NEST-02";
    private static final double NEST_02_LON = 115.867;
    private static final double NEST_02_LAT = 28.698;

    /** 演示船位：NEST-01 距此约 2.6 公里。 */
    private static final double SHIP_LON = 115.859;
    private static final double SHIP_LAT = 28.663;

    /** 本单载重 5.5 公斤：只有载重六公斤以上的三架机够用。 */
    private static final double PAYLOAD_KG = 5.5;

    private static NestEntity nest(String nestId, double lon, double lat, String models) {
        NestEntity nest = new NestEntity();
        nest.setNestId(nestId);
        nest.setLon(BigDecimal.valueOf(lon));
        nest.setLat(BigDecimal.valueOf(lat));
        nest.setCapacity(4);
        nest.setSupportedModels(models);
        nest.setStatus("可用");
        return nest;
    }

    private static DroneEntity drone(String droneId, String nestId, String model,
            double maxPayloadKg, double battery) {
        DroneEntity drone = new DroneEntity();
        drone.setDroneId(droneId);
        drone.setModel(model);
        drone.setStatus("空闲");
        drone.setHealthStatus("健康");
        drone.setMaxPayload(BigDecimal.valueOf(maxPayloadKg));
        drone.setBattery(BigDecimal.valueOf(battery));
        drone.setHomeNestId(nestId);
        drone.setCurrentNestId(nestId);
        drone.setPositionType("原巢");
        return drone;
    }

    /** 演示台账的机巢与无人机。 */
    private static List<NestEntity> nests() {
        return List.of(nest(NEST_01, NEST_01_LON, NEST_01_LAT, "M30T,M400"),
                nest(NEST_02, NEST_02_LON, NEST_02_LAT, "M30T,M3T"));
    }

    private static List<DroneEntity> drones() {
        return List.of(
                drone("UAV-01", NEST_01, "M30T", 6.0, 100.0),
                drone("UAV-02", NEST_01, "M400", 20.0, 98.0),
                drone("UAV-03", NEST_02, "M30T", 6.0, 95.0),
                drone("UAV-04", NEST_02, "M3T", 2.0, 92.0));
    }

    /** 订单：船位、载重与系统选定的货巢。 */
    private static OrderTaskEntity order(String cargoNestId) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORD-CHAIN-1");
        order.setShipLon(BigDecimal.valueOf(SHIP_LON));
        order.setShipLat(BigDecimal.valueOf(SHIP_LAT));
        order.setGoodsWeight(BigDecimal.valueOf(PAYLOAD_KG));
        order.setCargoNestId(cargoNestId);
        return order;
    }

    @Test
    @DisplayName("货巢已定：装配器把货巢翻译成货点坐标，生成器据此产出组合")
    void chain_shouldProduceCombosWhenCargoNestDecided() {
        CandidateInput input = CandidateInputAssembler.assemble(order(NEST_01), drones(), nests());

        assertFalse(input.hasUnresolvedCargoNest(), "前置：货巢坐标必须解析得出来");
        assertTrue(input.hasCargoPoint(), "前置：货点必须可用");

        List<CandidateGenerator.CandidateCombo> combos = new CandidateGenerator()
                .generate(input, CandidateGenerator.Params.defaults());

        assertFalse(combos.isEmpty(),
                "系统已选定货巢 " + NEST_01 + "，候选链必须能产出组合，否则订单会卡在调度中");
        assertTrue(combos.stream().allMatch(c -> NEST_01.equals(c.nestId())),
                "组合的装货巢必须恒为已定货巢 " + NEST_01);
        assertTrue(combos.stream().anyMatch(c -> "UAV-01".equals(c.droneId())),
                "NEST-01 的 M30T 机（载重六公斤、电量满）必须能到该货巢装货");
    }

    @Test
    @DisplayName("货巢已定：机型不被货巢允许的机不参与该巢的组合")
    void chain_shouldExcludeDroneWhoseTypeNotAllowedByCargoNest() {
        // NEST-02 只允许 M30T 与 M3T：机型不被允许的 UAV-02（M400）不得为该货巢出组合。
        // 其余机型被允许、载重够的机都参与——跨巢转场由会面求解按距离判定能不能赶上。
        CandidateInput input = CandidateInputAssembler.assemble(order(NEST_02), drones(), nests());

        List<CandidateGenerator.CandidateCombo> combos = new CandidateGenerator()
                .generate(input, CandidateGenerator.Params.defaults());

        assertFalse(combos.isEmpty(), "NEST-02 有 M30T 机可用，必须有组合");
        assertTrue(combos.stream().allMatch(c -> NEST_02.equals(c.nestId())),
                "组合的装货巢必须恒为已定货巢 " + NEST_02);
        assertEquals(0, combos.stream().filter(c -> "UAV-02".equals(c.droneId())).count(),
                "机型不被货巢允许的 UAV-02（M400）不得为该货巢出组合");
        assertEquals(1, combos.stream().filter(c -> "UAV-03".equals(c.droneId())).count(),
                "载重够且机型被允许的 UAV-03 必须为该货巢出组合");
    }

    @Test
    @DisplayName("货巢未定：仍走巢乘机双层枚举，产出组合的巢可不同于机所在巢")
    void chain_shouldKeepLegacyEnumerationWhenNoCargoNest() {
        CandidateInput input = CandidateInputAssembler.assemble(order(null), drones(), nests());

        assertFalse(input.hasCargoPoint(), "前置：没有货巢就没有货点");
        assertFalse(input.hasUnresolvedCargoNest(), "前置：没有货巢不是脏货巢");

        List<CandidateGenerator.CandidateCombo> combos = new CandidateGenerator()
                .generate(input, CandidateGenerator.Params.defaults());

        assertFalse(combos.isEmpty(), "没有货巢时必须仍能产出候选");
        assertTrue(combos.stream().anyMatch(c -> "UAV-01".equals(c.droneId())),
                "NEST-01 的机必须出现在候选里");
    }
}
