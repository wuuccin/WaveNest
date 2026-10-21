package com.waterlogistics.algo.selection;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.FeasibilityJudge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FeasibleCandidatePicker} 单测（C1 · 10-21 任务清单 · 卢肄文）。
 *
 * <p>核心就是清单 C1 的验收判据：
 * <b>注入 11 m/s 后 M30T（抗风 10.0）被淘汰、M400（12.0）保留</b>。
 * 附带的几条覆盖「近的不可飞要退到次优」「全不可飞如实返回」「电量闸同样生效」
 * 「未登记抗风按统一上限回落」「生成端顺序被保留」。
 *
 * <p>纯 JUnit：本类不依赖 Spring／DB／MQ，因此这条判据不必等数据库与消息队列就能验。
 */
class FeasibleCandidatePickerTest {

    /** 判定参数与 {@code DispatchOrchestrator.judgeParams()} 同口径（能耗用演示标定值）。 */
    private static FeasibilityJudge.Params params() {
        return new FeasibilityJudge.Params(12.0, 30.0, 1.5, 0.2);
    }

    /** M30T：抗风 10.0 m/s，离取货点近（1.5 km），电满。 */
    private static CandidateGenerator.CandidateCombo m30t() {
        return new CandidateGenerator.CandidateCombo("combo-m30t", "M30T", "nest-A", "drone-1",
                1.5, 95.0, 10.0, 15.0);
    }

    /** M400：抗风 12.0 m/s，离取货点远（4.0 km），电满。 */
    private static CandidateGenerator.CandidateCombo m400() {
        return new CandidateGenerator.CandidateCombo("combo-m400", "M400", "nest-A", "drone-2",
                4.0, 95.0, 12.0, 20.0);
    }

    @Test
    @DisplayName("★ 判据：注入 11 m/s → M30T（抗风 10.0）淘汰、M400（抗风 12.0）保留")
    void injected11ms_shouldDropM30tAndKeepM400() {
        // 生成端按距离升序：M30T 在前（1.5 km < 4.0 km），只看距离会选它
        List<CandidateGenerator.CandidateCombo> combos = List.of(m30t(), m400());

        FeasibleCandidatePicker.Selection selection =
                FeasibleCandidatePicker.pick(combos, 11.0, 10.0, params());

        assertTrue(selection.found(), "应选出可飞候选");
        assertEquals("M400", selection.chosen().type(), "抗风 12.0 的 M400 必须保留");
        assertEquals(1, selection.rejectedCount(), "只应淘汰 M30T");
        assertEquals(2, selection.evaluated().size(), "两个候选都要被判过");

        FeasibilityJudge.FeasibilityResult dropped = selection.evaluated().get(0);
        assertEquals("combo-m30t", dropped.comboId());
        assertFalse(dropped.feasible());
        assertTrue(dropped.reasons().get(0).contains("机型抗风不足"),
                "淘汰原因要能说清是抗风不足，实际=" + dropped.reasons());
        assertTrue(dropped.reasons().get(0).contains("11.0"),
                "原因里应带上现场风速，实际=" + dropped.reasons());
    }

    @Test
    @DisplayName("风速低于两者抗风上限 → 仍选最近的那个（不被判定倒挂成最远的）")
    void calmWind_shouldStillPickNearest() {
        FeasibleCandidatePicker.Selection selection = FeasibleCandidatePicker.pick(
                List.of(m30t(), m400()), 9.5, 10.0, params());

        assertTrue(selection.found());
        assertEquals("M30T", selection.chosen().type(), "风小的时候不该反而去选远的");
        assertEquals(0, selection.rejectedCount());
        assertEquals("（全部可行）", selection.rejectionSummary());
    }

    @Test
    @DisplayName("近的不可飞要退到次优，而不是整单放弃")
    void nearestInfeasible_shouldFallBackToNext() {
        // 把 M400 放到更远、M30T 放更近：11 m/s 下必须退到 M400
        CandidateGenerator.CandidateCombo nearInfeasible = new CandidateGenerator.CandidateCombo(
                "combo-near", "M30T", "nest-A", "drone-1", 0.5, 95.0, 10.0, 15.0);
        FeasibleCandidatePicker.Selection selection = FeasibleCandidatePicker.pick(
                List.of(nearInfeasible, m400()), 11.0, 10.0, params());

        assertTrue(selection.found());
        assertEquals("combo-m400", selection.chosen().comboId());
    }

    @Test
    @DisplayName("全部不可飞：如实返回未选出 ＋ 可读的淘汰摘要（供现场定位是风大还是电少）")
    void allInfeasible_shouldReturnNotFoundWithSummary() {
        FeasibleCandidatePicker.Selection selection =
                FeasibleCandidatePicker.pick(List.of(m30t(), m400()), 13.0, 10.0, params());

        assertFalse(selection.found());
        assertNull(selection.chosen());
        assertEquals(2, selection.rejectedCount());
        String summary = selection.rejectionSummary();
        assertTrue(summary.contains("combo-m30t") && summary.contains("combo-m400"),
                "摘要要能指到具体组合，实际=" + summary);
        assertTrue(summary.contains("机型抗风不足"), "摘要要带原因，实际=" + summary);
    }

    @Test
    @DisplayName("电量闸同样生效：抗风够但电不够也要淘汰")
    void lowBattery_shouldBeRejectedByBatteryGate() {
        // 85% 电、往返 2×0.5km 仅耗 1.5%，够用；换成 40% 电、巨大往返距离则不够
        CandidateGenerator.CandidateCombo healthy = new CandidateGenerator.CandidateCombo(
                "combo-healthy", "M400", "nest-A", "drone-2", 0.5, 85.0, 12.0, 20.0);
        CandidateGenerator.CandidateCombo drained = new CandidateGenerator.CandidateCombo(
                "combo-drained", "M30T", "nest-A", "drone-1", 30.0, 40.0, 10.0, 15.0);

        FeasibleCandidatePicker.Selection selection =
                FeasibleCandidatePicker.pick(List.of(drained, healthy), 5.0, 0.0, params());

        assertTrue(selection.found());
        assertEquals("combo-healthy", selection.chosen().comboId());
        assertTrue(selection.evaluated().get(0).reasons().get(0).contains("电量不足"),
                "电量闸的淘汰原因要能读出来，实际=" + selection.evaluated().get(0).reasons());
    }

    @Test
    @DisplayName("机型抗风未登记（NaN）：按统一上限回落，并在原因里写明是回落口径")
    void unregisteredWindLimit_shouldFallBackToMaxWind() {
        CandidateGenerator.CandidateCombo unregistered = new CandidateGenerator.CandidateCombo(
                "combo-na", "UNKNOWN", "nest-A", "drone-9", 1.0, 95.0, Double.NaN, 15.0);

        // 11 m/s < 统一上限 12.0 → 放行
        assertTrue(FeasibleCandidatePicker.pick(List.of(unregistered), 11.0, 10.0, params()).found());

        // 13 m/s > 统一上限 → 淘汰，且原因里点明「机型抗风未登记」
        FeasibleCandidatePicker.Selection rejected =
                FeasibleCandidatePicker.pick(List.of(unregistered), 13.0, 10.0, params());
        assertFalse(rejected.found());
        assertTrue(rejected.evaluated().get(0).reasons().get(0).contains("机型抗风未登记"),
                "回落口径必须写进原因，实际=" + rejected.evaluated().get(0).reasons());
    }

    @Test
    @DisplayName("空候选列表：合法输入，返回未选出并给出空列表说明")
    void emptyCombos_shouldBeLegal() {
        FeasibleCandidatePicker.Selection selection =
                FeasibleCandidatePicker.pick(List.of(), 8.0, 10.0, params());
        assertFalse(selection.found());
        assertEquals(0, selection.evaluated().size());
        assertTrue(selection.rejectionSummary().contains("候选列表为空"));
    }

    @Test
    @DisplayName("保留生成端顺序：可行者不止一个时取第一个（＝最近）")
    void shouldKeepGeneratorOrder() {
        CandidateGenerator.CandidateCombo farther = new CandidateGenerator.CandidateCombo(
                "combo-far", "M400", "nest-A", "drone-2", 4.0, 95.0, 12.0, 20.0);
        CandidateGenerator.CandidateCombo nearer = new CandidateGenerator.CandidateCombo(
                "combo-near", "M400", "nest-A", "drone-3", 1.0, 95.0, 12.0, 20.0);

        // 故意把「远」放前面，验证本类不擅自重排——顺序由生成端负责
        assertSame(farther, FeasibleCandidatePicker.pick(
                List.of(farther, nearer), 5.0, 10.0, params()).chosen());
    }

    @Test
    @DisplayName("入参校验：风速／载荷非法、参数为 null 都要拒绝")
    void validation_shouldRejectBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> FeasibleCandidatePicker.pick(List.of(m30t()), 5.0, 10.0, null));
        assertThrows(IllegalArgumentException.class,
                () -> FeasibleCandidatePicker.pick(List.of(m30t()), Double.NaN, 10.0, params()));
        assertThrows(IllegalArgumentException.class,
                () -> FeasibleCandidatePicker.pick(List.of(m30t()), -1.0, 10.0, params()));
        assertThrows(IllegalArgumentException.class,
                () -> FeasibleCandidatePicker.pick(List.of(m30t()), 5.0, -1.0, params()));
    }
}
