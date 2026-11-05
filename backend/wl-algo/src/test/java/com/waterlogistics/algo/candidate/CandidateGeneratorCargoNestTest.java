package com.waterlogistics.algo.candidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 11-05 序号 1 · 硬判据 1 的「脏货巢」语义（对照卢肄文 11-05 字段表原话）。
 *
 * <h3>为什么单独一个类</h3>
 * <p>{@link CandidateInput} 里「货点可用／不可用」有两种成因，11-05 之前只有
 * {@link CandidateInput#hasCargoPoint()} 一个判断，两者被合并成同一个分支
 * 「退回船位口径」。但卢肄文 11-05 字段表明确：
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
    @DisplayName("11-05-1 · 货巢坐标齐全时距离基准是货点，不是船位")
    void resolvedCargoNest_usesCargoPointAsDistanceBase() {
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-01", 115.848, 28.642)),
                List.of(drone("UAV-01", "NEST-01")),
                "NEST-02", 115.867, 28.698);

        assertFalse(input.hasUnresolvedCargoNest(), "前置：不是脏货巢");
        assertTrue(input.hasCargoPoint(), "前置：货点可用");
        assertEquals(115.867, input.distanceBaseLon(), 1e-9);
        assertEquals(28.698, input.distanceBaseLat(), 1e-9);

        // 实算：NEST-01 → NEST-02 直线 6.50 km（> 默认半径 5 km），
        // 而 NEST-01 → 船位只有 2.96 km（< 5 km）。
        // 所以「按货点算」⇒ 0 条，「误退回船位」⇒ 1 条，两者可区分。
        assertEquals(List.of(),
                generator.generate(input, CandidateGenerator.Params.defaults()),
                "距离必须按货点算（6.50 km 超半径 ⇒ 不产出）；若产出说明退回了船位口径（2.96 km）");
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

    // ───────────── 跨巢：硬判据 1 本体 ─────────────

    @Test
    @DisplayName("11-05-1 · 候选中出现「非无人机当前所在巢」的组合（硬判据 1 本体）")
    void crossNestComboIsProduced() {
        // 两巢两机，货点在两巢中间（离各自约 3.3 km，均在默认 5 km 半径内）：
        // 期望 4 条（2 巢 × 2 机全组合），其中「UAV-N 从 NEST-S 出发」「UAV-S 从 NEST-N 出发」
        // 两条跨巢组合正是硬判据 1 要求旧实现产不出的那两条。
        CandidateInput input = new CandidateInput(PICKUP_LON, PICKUP_LAT, PAYLOAD_KG,
                List.of(nest("NEST-S", 115.848, 28.642), nest("NEST-N", 115.867, 28.698)),
                List.of(drone("UAV-S", "NEST-S"), drone("UAV-N", "NEST-N")),
                "NEST-MID", 115.8575, 28.670);

        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(input, CandidateGenerator.Params.defaults());

        assertEquals(4, combos.size(),
                "两巢都在 5 km 半径内，巢×机应出 4 条；实际：" + combos.size());
        assertEquals(1, combos.stream()
                .filter(c -> "UAV-N".equals(c.droneId()) && "NEST-S".equals(c.nestId()))
                .count(),
                "北巢的机从南巢出发的组合必须出现——这正是旧实现 nestMap.get(d.nestId()) 产不出的");
        assertEquals(1, combos.stream()
                .filter(c -> "UAV-S".equals(c.droneId()) && "NEST-N".equals(c.nestId()))
                .count(),
                "南巢的机从北巢出发的组合必须出现");
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
