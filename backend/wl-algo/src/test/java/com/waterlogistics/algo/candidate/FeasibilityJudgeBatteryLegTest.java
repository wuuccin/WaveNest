package com.waterlogistics.algo.candidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电量闸三段里程口径的回归（Open CodeReview 指出的「巢→取货段未计入」已修）。
 *
 * <h3>原缺陷</h3>
 * <p>真实航程是「巢→取货→交货→巢」三段，旧口径只算 {@code 2×taskDistKm}
 * （取货↔交货往返），<b>机巢→取货的往返整段漏掉</b>——电量闸系统性偏乐观。
 * 具体后果：机巢离取货点越远（candidate 越依赖长距离摆渡），漏算越多，
 * 一架「看着近、实际飞不到」的候选会被判 FEASIBLE。
 *
 * <h3>新口径</h3>
 * <p>{@code totalKm = 2×(taskDistKm + combo.distKm())}：
 * combo 自带的 distKm 就是生成器用 haversine 算好的「巢→取货点」距离；
 * 交货→巢一段无交货点坐标，按保守上界（巢→取＋取→交）并入——宁可多算，不漏算。
 *
 * <h3>旧口径必红对照</h3>
 * <p>用例 {@link #distantNestComboIsRejectedByBatteryGate()}：
 * 旧口径下两个候选都会 FEASIBLE（漏算后余电充足），新口径下巢远的必须淘汰。
 */
class FeasibilityJudgeBatteryLegTest {

    private final FeasibilityJudge judge = new FeasibilityJudge();

    /** 取↔交 3.0 km；电量 60%；保留 20%；1.5%/km + 0.2%/kg（与 sys_param 默认一致） */
    private static final double TASK_DIST_KM = 3.0;
    private static final double PAYLOAD_KG = 2.0;
    private static final FeasibilityJudge.Params PARAMS =
            new FeasibilityJudge.Params(12.0, 20.0, 1.5, 0.2);

    private static CandidateGenerator.CandidateCombo combo(double nestToPickupKm) {
        return new CandidateGenerator.CandidateCombo(
                "CMB-T01", "M30T", "NEST-01", "UAV-01",
                nestToPickupKm, 60.0, 12.0, 12.0);
    }

    @Test
    @DisplayName("三段口径：巢近（1 km）候选总里程 8 km，应 FEASIBLE 且原因里能算出账")
    void nearbyNestComboStaysFeasible() {
        FeasibilityJudge.FeasibilityResult r =
                judge.judge(combo(1.0), TASK_DIST_KM, 5.0, PAYLOAD_KG, PARAMS);

        assertTrue(r.feasible(), "巢近候选：2×(3+1)=8 km × 1.5 = 12 + 0.4 载荷 ≈ 12.4，余 47.6 ≥ 20");
    }

    @Test
    @DisplayName("三段口径：巢远（15 km）候选必须被电量闸淘汰——旧口径（只算 2×3km）会放行")
    void distantNestComboIsRejectedByBatteryGate() {
        FeasibilityJudge.FeasibilityResult r =
                judge.judge(combo(15.0), TASK_DIST_KM, 5.0, PAYLOAD_KG, PARAMS);

        // 旧口径：2×3=6 km → 耗 9.4，余 50.6 ≥ 20 → FEASIBLE（缺陷：机巢段整段漏掉）
        // 新口径：2×(3+15)=36 km → 耗 54+0.4=54.4，余 5.6 < 20 → INFEASIBLE
        assertFalse(r.feasible(),
                "巢远 15 km 的候选往返里程 36 km，余电 5.6% < 保留 20%，必须淘汰"
                        + "——旧口径漏算机巢段会把它放行");
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("往返里程 36.0km")),
                "淘汰原因应给出往返总里程 36.0 km，实际：" + r.reasons());
    }

    /**
     * 淘汰原因里机巢段与主航段分开列账，现场能看出是哪段在吃电。
     *
     * <p>断言用「带单位的整段文案」而不是 {@code contains("36.0")} 这类半截数字：
     * {@code "6.0"} 会被 {@code "36.0"} 命中、{@code "30.0"} 会被 {@code "30.0km"} 命中，
     * 半截匹配会让文案写错也照样变绿（逐字审查时发现原断言正是这个问题）。
     */
    @Test
    @DisplayName("淘汰原因里机巢段与主航段分开列账，现场能看出是哪段在吃电")
    void rejectionReasonBreaksDownBothLegs() {
        FeasibilityJudge.FeasibilityResult r =
                judge.judge(combo(15.0), TASK_DIST_KM, 5.0, PAYLOAD_KG, PARAMS);

        String reason = r.reasons().stream()
                .filter(s -> s.contains("电量不足"))
                .findFirst()
                .orElseThrow();
        assertTrue(reason.contains("巢↔取 30.0"), "原因应含机巢段往返 30.0 km（2×15）：" + reason);
        assertTrue(reason.contains("取↔交 6.0"), "原因应含主航段往返 6.0 km（2×3）：" + reason);
        assertTrue(reason.contains("往返里程 36.0km"),
                "两段之和应等于往返总里程 36.0 km，现场才能按文案复算核对：" + reason);
    }

    /**
     * 组合自带距离是 {@code NaN}／负数时同样必须抛（HIGH.8 换了入口）。
     *
     * <p>{@code combo} 由调用方构造，测试/旁路可以绕开生成器直接 new；
     * 若 {@code combo.distKm()} 为 NaN，{@code totalKm} 也变 NaN，
     * 于是 {@code NaN < 保留} 恒 false ⇒ 电量闸被静默放行——与 HIGH.8 是同一个病。
     */
    @Test
    @DisplayName("组合距离为 NaN／负数时必须抛，不能把电量闸变成永真")
    void nonFiniteComboDistanceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo(Double.NaN), TASK_DIST_KM, 5.0, PAYLOAD_KG, PARAMS),
                "combo.distKm 为 NaN 时必须抛，否则往返里程 NaN、电量闸恒过");
        assertThrows(IllegalArgumentException.class,
                () -> judge.judge(combo(-1.0), TASK_DIST_KM, 5.0, PAYLOAD_KG, PARAMS),
                "combo.distKm 为负会让能耗变负、电量闸永远通过");
    }
}
