package com.waterlogistics.algo.candidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 11-01 评审 HIGH.8 ＋ MEDIUM.16 的回归（对应当日硬判据 3、4）。
 *
 * <h3>HIGH.8：NaN／负距离让全部候选判 FEASIBLE（硬判据 3）</h3>
 * <p>判定用的是 {@code >}／{@code <}，而 <b>{@code NaN} 参与任何比较都返回 {@code false}</b>：
 * <ul>
 *   <li>{@code worstWindMs = NaN} ⇒ {@code NaN > 抗风上限} 为 false ⇒ 风况闸放行；</li>
 *   <li>{@code distKm = NaN} ⇒ {@code remaining = NaN}、{@code NaN < 保留} 为 false ⇒ 电量闸放行。</li>
 * </ul>
 * 两道闸同时失效，结果是「风再大、电再少，全部候选都是 FEASIBLE」。
 * 负距离同理：能耗算成负数，电量闸永远通过。
 * <p>本类钉住：这类输入必须在<b>入口</b>抛异常，而不是给出 FEASIBLE 或 INFEASIBLE。
 *
 * <h3>MEDIUM.16：未登记抗风反而最宽松（硬判据 4）</h3>
 * <p>原回落值取 {@code criticalWindMs = 12.0}（气象熔断阈值、已登记机型里最高的一档），
 * 于是「抗风没登记」比「登记成 10.0」更能扛风。现在回落上限由
 * {@code CandidateFeasibilityPlanner.conservativeWindFallbackMs} 给出保守值，
 * 本类在判定层验证「保守上限确实比 12.0 严」，planner 侧的取值由
 * {@code ConservativeWindFallbackTest} 覆盖。
 */
class FeasibilityJudgeGuardTest {

    private final CandidateGenerator generator = new CandidateGenerator();
    private final FeasibilityJudge judge = new FeasibilityJudge();

    private static final double DIST_KM = 1.0;
    private static final double PAYLOAD_KG = 2.0;

    /** 放宽的统一上限（12.0，即原回落值）——只用来证明「不是上限本身在拦」 */
    private static final FeasibilityJudge.Params LOOSE_PARAMS =
            new FeasibilityJudge.Params(12.0, 20.0, 1.5, 0.2);

    private static CandidateInput oneDrone(Double windLimit) {
        return new CandidateInput(
                115.8611, 28.6660, PAYLOAD_KG,
                List.of(new CandidateInput.NestView("NEST-01", 115.848, 28.642, List.of("M30T"))),
                List.of(new CandidateInput.DroneView("UAV-01", "NEST-01", "M30T", "空闲",
                        90.0, 6.0, windLimit, 12.0)));
    }

    private CandidateGenerator.CandidateCombo combo(Double windLimit) {
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(oneDrone(windLimit), CandidateGenerator.Params.defaults());
        assertEquals(1, combos.size(), "前置：应生成 1 个候选");
        return combos.get(0);
    }

    // ───────────── HIGH.8 · 硬判据 3 ─────────────

    @Test
    @DisplayName("硬判据3：最不利风为 NaN 时必须抛，不能被静默判 FEASIBLE")
    void nanWindIsRejected() {
        var combo = combo(10.0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, DIST_KM, Double.NaN, PAYLOAD_KG, LOOSE_PARAMS),
                "NaN 风速下风况闸会因 NaN 比较恒 false 而放行，必须在入口拦住");
        assertTrue(ex.getMessage().contains("worstWindMs"), "异常信息应指出是哪个参数：" + ex.getMessage());
    }

    @Test
    @DisplayName("硬判据3：任务距离为 NaN 时必须抛（否则电量闸 NaN 比较放行）")
    void nanDistanceIsRejected() {
        var combo = combo(12.0);
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, Double.NaN, 1.0, PAYLOAD_KG, LOOSE_PARAMS));
    }

    @Test
    @DisplayName("硬判据3：负距离／负风速必须抛（负距离会让能耗变负、电量闸永远通过）")
    void negativeInputsAreRejected() {
        var combo = combo(12.0);
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, -1.0, 1.0, PAYLOAD_KG, LOOSE_PARAMS), "负距离应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, DIST_KM, -0.1, PAYLOAD_KG, LOOSE_PARAMS), "负风速应拒绝");
    }

    @Test
    @DisplayName("硬判据3：±Infinity 同样拒绝（Infinity 会让风况闸恒真/恒假，不是合法观测值）")
    void infiniteInputsAreRejected() {
        var combo = combo(12.0);
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, DIST_KM, Double.POSITIVE_INFINITY, PAYLOAD_KG, LOOSE_PARAMS));
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo, Double.POSITIVE_INFINITY, 1.0, PAYLOAD_KG, LOOSE_PARAMS));
    }

    @Test
    @DisplayName("反向验证：正常输入仍能判出 FEASIBLE / INFEASIBLE（守卫没把正常路径一起拦死）")
    void normalInputsStillJudge() {
        var m30t = combo(10.0);
        assertTrue(judge.judge(m30t, DIST_KM, 9.0, PAYLOAD_KG, LOOSE_PARAMS).feasible(),
                "9 m/s < 10.0 应通过");
        assertFalse(judge.judge(m30t, DIST_KM, 11.0, PAYLOAD_KG, LOOSE_PARAMS).feasible(),
                "11 m/s > 10.0 应淘汰");
    }

    // ───────────── MEDIUM.16 · 硬判据 4 ─────────────

    @Test
    @DisplayName("硬判据4：未登记抗风的机型，在保守上限下 11 m/s 必须淘汰（原 12.0 口径会存活）")
    void unregisteredTypeIsNotTheMostPermissive() {
        var unregistered = combo(Double.NaN);
        // 保守上限取 8.0（预警阈值）——本项目实际使用的回落值
        FeasibilityJudge.Params conservative = new FeasibilityJudge.Params(8.0, 20.0, 1.5, 0.2);

        assertFalse(judge.judge(unregistered, DIST_KM, 11.0, PAYLOAD_KG, conservative).feasible(),
                "未登记机型在 11 m/s 下必须淘汰");
        assertFalse(judge.judge(unregistered, DIST_KM, 9.0, PAYLOAD_KG, conservative).feasible(),
                "未登记机型在 9 m/s（> 8.0 保守上限）下也必须淘汰");

        // 对照：同一阵风 11 m/s 用旧回落值 12.0 判，未登记机型会存活——这正是原缺陷
        assertTrue(judge.judge(unregistered, DIST_KM, 11.0, PAYLOAD_KG, LOOSE_PARAMS).feasible(),
                "反向对照：旧回落值 12.0 下未登记机型会放行（说明本次修的是真实缺陷）");
    }

    @Test
    @DisplayName("硬判据4：未登记的淘汰原因要写明「按保守口径判」，便于现场区分回落与真值")
    void unregisteredReasonExplainsConservativeFallback() {
        var unregistered = combo(Double.NaN);
        var r = judge.judge(unregistered, DIST_KM, 9.0, PAYLOAD_KG,
                new FeasibilityJudge.Params(8.0, 20.0, 1.5, 0.2));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("未登记") && s.contains("保守口径")),
                "原因应写明未登记且按保守口径判，实际：" + r.reasons());
    }
}
