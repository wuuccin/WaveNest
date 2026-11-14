package com.waterlogistics.algo.candidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选生成在「货巢已定」下的语义测试。
 *
 * <h3>为什么单独一个类</h3>
 * <p>{@link CandidateInput} 里「货点可用／不可用」有两种成因，此前只有
 * {@link CandidateInput#hasCargoPoint()} 一个判断，两者被合并成同一个分支
 * 「退回船位口径」。但货巢字段表明确：
 * <blockquote>
 *   脏货巢查不到坐标时应<b>不提供该组合</b>，不要退化到「无人机所在巢」，
 *   否则货巢≠机巢的空机转场段会凭空消失。
 * </blockquote>
 * 于是必须拆成：
 * <ul>
 *   <li>{@code cargoNestId == null} → 旧口径调用方，<b>退回船位是对的</b>；</li>
 *   <li>{@code cargoNestId != null && 坐标非有限} → 脏货巢，<b>一个组合都不产出</b>。</li>
 * </ul>
 * 本类钉住第二档，以及「第一档没有被误伤」。
 *
 * <h3>货巢已定分支</h3>
 * <p>货巢由系统在下单时刻选定并落库后，组合固定为「已定货巢 ＋ 任一空闲机」，
 * 距离语义是「机归属巢到装货点」。本类同时钉住这一分支：装货巢不得被换成机所在的巢，
 * 跨巢体现在「机从别的巢飞来」。
 */
class CandidateGeneratorCargoNestTest {

    private final CandidateGenerator generator = new CandidateGenerator();

    private static final double PICKUP_LON = 115.8611;
    private static final double PICKUP_LAT = 28.6660;
    private static final double PAYLOAD_KG = 2.0;

    private static CandidateInput.NestView nest(String id, double lon, double lat) {
        return new CandidateInput.NestView(id, lon, lat, List.of("M30T"));
    }

    private static CandidateInput.DroneView drone(String id, String nestId) {
        return new CandidateInput.DroneView(id, nestId, "M30T", "空闲", 90.0, 6.0, 10.0, 12.0);
    }

    private static CandidateInput.DroneView droneAt(String id, String nestId, String status) {
        return new CandidateInput.DroneView(id, nestId, "M30T", status, 90.0, 6.0, 10.0, 12.0);
    }

    // ───────────── 脏货巢：不产出 ─────────────

    @Test
    @DisplayName("11-05-1 · 货巢已指定但坐标缺失（NaN）时一个组合都不产出")
    void dirtyCargoNest_yieldsNoCombos() {
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642), nest("NEST-02", 115.867, 28.698)),
                List.of(drone("UAV-01", "NEST-01"), drone("UAV-02", "NEST-02")),
                "NEST-03", Double.NaN, Double.NaN);

        assertTrue(input.hasUnresolvedCargoNest(), "前置：应识别为脏货巢");
        assertFalse(input.hasCargoPoint(), "前置：货点应判不可用");

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());

        assertEquals(List.of(), combos,
                "脏货巢不得退化到船位口径——否则产出的组合距离基准是船位，"
                        + "而货在巢里，现场看不出任何异常");
    }

    @Test
    @DisplayName("11-05-1 · 脏货巢即便巢与机都齐全、距离也在半径内，仍然不产出")
    void dirtyCargoNest_doesNotFallBackEvenWhenEverythingElseIsHealthy() {
        // 只给一个巢、一架机，且巢离船位很近（<5 km）——退回船位口径时必然产出 1 条。
        // 这样断言「0 条」才真正证明是脏货巢闸在拦，而不是别的原因碰巧拦住了。
        CandidateInput fallbackWouldMatch = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642)),
                List.of(drone("UAV-01", "NEST-01")),
                "NEST-99", Double.NaN, Double.NaN);

        assertEquals(List.of(),
                generator.generate(fallbackWouldMatch, CandidateGenerator.Params.defaults()));

        // 对照：同样的巢与机，只要不带货巢（旧口径），就应该照常产出 1 条
        CandidateInput legacy = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642)),
                List.of(drone("UAV-01", "NEST-01")));
        assertEquals(1, generator.generate(legacy, CandidateGenerator.Params.defaults()).size(),
                "对照：未给货巢的旧口径调用方必须照常产出，不能被脏货巢闸误伤");
    }

    @Test
    @DisplayName("11-05-1 · 货巢坐标只有一个非有限（NaN 经度／NaN 纬度）同样算脏货巢")
    void dirtyCargoNest_halfMissingCoordinateAlsoBlocks() {
        for (double[] pair : new double[][] {
                {Double.NaN, 28.698},
                {115.867, Double.NaN} }) {
            CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                    List.of(nest("NEST-01", 115.848, 28.642)),
                    List.of(drone("UAV-01", "NEST-01")),
                    "NEST-02", pair[0], pair[1]);

            assertTrue(input.hasUnresolvedCargoNest(),
                    "经度或纬度任一非有限都应判脏货巢（lon=" + pair[0] + ", lat=" + pair[1] + "）");
            assertEquals(List.of(), generator.generate(input, CandidateGenerator.Params.defaults()));
        }
    }

    // ───────────── 正常货巢：按货点算距离 ─────────────

    @Test
    @DisplayName("货巢坐标齐全但该巢不在本轮可用巢列表里时，一个组合都不产出")
    void cargoNestOutsideAvailableNests_yieldsNoCombos() {
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642)),
                List.of(drone("UAV-01", "NEST-01")),
                "NEST-02", 115.867, 28.698);

        assertFalse(input.hasUnresolvedCargoNest(), "前置：不是脏货巢");
        assertTrue(input.hasCargoPoint(), "前置：货点可用");
        assertEquals(115.867, input.distanceBaseLon(), 1e-9);
        assertEquals(28.698, input.distanceBaseLat(), 1e-9);

        // 货巢已定分支的外层只枚举已定货巢这一个巢；该巢不在可用巢列表里时无巢可枚举，
        // 于是不产出任何组合——不能退而选别的巢，那会让装货点与订单记的货巢不一致。
        assertEquals(List.of(),
                generator.generate(input, CandidateGenerator.Params.defaults()),
                "货巢不在可用巢列表时不产出任何组合，不得改选别的巢");
    }

    @Test
    @DisplayName("11-05-1 · 货巢＝机巢时结果与旧口径逐字段一致")
    void cargoNestEqualsHomeNest_matchesLegacyShape() {
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642)),
                List.of(drone("UAV-01", "NEST-01")),
                "NEST-01", 115.848, 28.642);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());
        assertEquals(1, combos.size(), "货巢＝机巢时应照常产出 1 条");
        CandidateGenerator.CandidateCombo combo = combos.get(0);
        assertEquals("NEST-01", combo.nestId());
        assertEquals("UAV-01", combo.droneId());
        assertEquals("M30T", combo.type());
    }

    // ───────────── 货巢已定：组合固定为装货巢 ─────────────

    @Test
    @DisplayName("货巢已定：组合固定为装货巢，机可来自其它巢（跨巢转场）")
    void fixedCargoNest_producesCombosForDronesFromOtherNests() {
        // 装货点固定在 NEST-MID，两架机分别停在 NEST-S 与 NEST-N：
        // 期望两条组合，装货巢都是 NEST-MID，机各一台——跨巢体现在「机从别的巢飞来」，
        // 而不是「组合的装货巢被换掉」。
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-MID", 115.8575, 28.670),
                        nest("NEST-S", 115.848, 28.642),
                        nest("NEST-N", 115.867, 28.698)),
                List.of(drone("UAV-S", "NEST-S"), drone("UAV-N", "NEST-N")),
                "NEST-MID", 115.8575, 28.670);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());

        assertEquals(2, combos.size(),
                "两架机各产出一个组合，实际：" + combos.size());
        assertTrue(combos.stream().allMatch(c -> "NEST-MID".equals(c.nestId())),
                "组合的装货巢必须恒为已定货巢 NEST-MID，不得被换成机所在的巢");
        assertEquals(1, combos.stream().filter(c -> "UAV-N".equals(c.droneId())).count(),
                "北巢的机必须能到装货巢装货");
        assertEquals(1, combos.stream().filter(c -> "UAV-S".equals(c.droneId())).count(),
                "南巢的机必须能到装货巢装货");
    }

    @Test
    @DisplayName("货巢已定：距离是「机归属巢到装货点」，远的机排在后面而不是被剔除")
    void fixedCargoNest_distanceIsHomeNestToCargoNest() {
        // 装货点 NEST-CARGO 在两巢中间；UAV-NEAR 距装货点约 3.3 公里，
        // UAV-FAR 距装货点几十公里。两台机都参与组合——能不能赶到由会面求解判定、
        // 够不够电由电量闸判定，候选生成不另设距离闸；远的排在后面。
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-CARGO", 115.8575, 28.670),
                        nest("NEST-NEAR", 115.848, 28.642),
                        nest("NEST-FAR", 116.5, 29.5)),
                List.of(drone("UAV-NEAR", "NEST-NEAR"), drone("UAV-FAR", "NEST-FAR")),
                "NEST-CARGO", 115.8575, 28.670);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());

        assertEquals(2, combos.size(),
                "两台机各产出一个组合，实际：" + combos.size());
        assertTrue(combos.stream().allMatch(c -> "NEST-CARGO".equals(c.nestId())),
                "组合的装货巢必须恒为已定货巢");
        assertEquals("UAV-NEAR", combos.get(0).droneId(),
                "离装货点近的机排在前面");
        assertEquals("UAV-FAR", combos.get(1).droneId(),
                "离装货点远的机排在后面");
        assertTrue(combos.get(0).distKm() > 0.0 && combos.get(0).distKm() < 5.0,
                "距离是机归属巢到装货点的距离，实际：" + combos.get(0).distKm());
        assertTrue(combos.get(1).distKm() > combos.get(0).distKm(),
                "排序必须按机归属巢到装货点的距离升序");
    }

    @Test
    @DisplayName("货巢已定：机归属巢坐标查不到时该机不参与组合")
    void fixedCargoNest_skipsDroneWithUnknownHomeNest() {
        // UAV-ORPHAN 的归属巢不在本轮可用巢台账里：算不出空机转场距离，
        // 产出一个距离为零的假组合会让电量闸与时限闸失去依据，因此不产出。
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-CARGO", 115.8575, 28.670)),
                List.of(drone("UAV-ORPHAN", "NEST-GONE")),
                "NEST-CARGO", 115.8575, 28.670);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());

        assertEquals(List.of(), combos,
                "归属巢查不到坐标的机不参与组合——距离为假的组合会骗过电量闸");
    }

    @Test
    @DisplayName("11-05-1 · 状态非空闲的机不参与任何巢的组合")
    void busyDroneProducesNoCombo() {
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-S", 115.848, 28.642), nest("NEST-N", 115.867, 28.698)),
                List.of(droneAt("UAV-BAD", "NEST-S", "故障"), drone("UAV-OK", "NEST-S")),
                "NEST-S", 115.848, 28.642);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());
        assertEquals(0, combos.stream().filter(c -> "UAV-BAD".equals(c.droneId())).count());
    }
}
