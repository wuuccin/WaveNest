package com.waterlogistics.algo.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FeasibleCandidatePicker} 延误惩罚一级比较键（11-05 序号 2 · 张睿哲）。
 *
 * <p>硬判据 5 后半：「deadline 更紧时延误项在打分中权重更高」——
 * 交期紧张时「远一点但更快（巡航高）」的组合应赢过「近而慢」；
 * 无死线 / 权重为 0 时行为与旧口径逐位一致（生成端距离序取首个可行者）。</p>
 */
class FeasibleCandidatePickerDelayTest {

    /** 与 sys_param 默认一致的判定参数；风况 5 m/s、载荷 1 kg，两候选均可行 */
    private static final FeasibilityJudge.Params PARAMS = new FeasibilityJudge.Params(12.0, 20.0, 1.5, 0.2);
    private static final double WIND = 5.0;
    private static final double PAYLOAD = 1.0;
    private static final long NOW = 1_700_000_000_000L;

    /** 近而慢：2 km @ 5 m/s → eta = now + 400 s */
    private static CandidateGenerator.CandidateCombo nearSlow() {
        return new CandidateGenerator.CandidateCombo(
                "CMB-NEAR", "M30T", "NEST-01", "UAV-01", 2.0, 90.0, 12.0, 5.0);
    }

    /** 远而快：3 km @ 15 m/s → eta = now + 200 s */
    private static CandidateGenerator.CandidateCombo farFast() {
        return new CandidateGenerator.CandidateCombo(
                "CMB-FAR", "M350", "NEST-02", "UAV-02", 3.0, 90.0, 12.0, 15.0);
    }

    @Test
    @DisplayName("紧死线（now+300s）：近而慢迟到 100s、远而快准时 → 远而快赢")
    void tightDeadline_farFastBeatsNearSlow() {
        long deadline = NOW + 300_000L;
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(nearSlow(), farFast()), WIND, PAYLOAD, PARAMS, deadline, 1.0, NOW);

        assertEquals("CMB-FAR", s.chosen().comboId(),
                "紧死线下延误惩罚应让「远而快」赢过「近而慢」");
    }

    @Test
    @DisplayName("松死线（now+1h）：两者都准时、延误分同为 0 → 退回 distKm，近者赢")
    void looseDeadline_nearestWins() {
        long deadline = NOW + 3_600_000L;
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(nearSlow(), farFast()), WIND, PAYLOAD, PARAMS, deadline, 1.0, NOW);

        assertEquals("CMB-NEAR", s.chosen().comboId(), "都能准时时应选最近者");
    }

    @Test
    @DisplayName("无死线（deadline=null）：即使有权重也退回旧口径 → 近者赢")
    void nullDeadline_legacyNearestWins() {
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(nearSlow(), farFast()), WIND, PAYLOAD, PARAMS, null, 1.0, NOW);

        assertEquals("CMB-NEAR", s.chosen().comboId());
    }

    @Test
    @DisplayName("权重=0：延误项关闭 → 近者赢")
    void zeroWeight_legacyNearestWins() {
        long deadline = NOW + 300_000L;
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(nearSlow(), farFast()), WIND, PAYLOAD, PARAMS, deadline, 0.0, NOW);

        assertEquals("CMB-NEAR", s.chosen().comboId());
    }

    @Test
    @DisplayName("旧四参签名委托新签名：行为与历史版本一致 → 近者赢")
    void legacyOverload_unchanged() {
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(nearSlow(), farFast()), WIND, PAYLOAD, PARAMS);

        assertEquals("CMB-NEAR", s.chosen().comboId());
    }

    @Test
    @DisplayName("权重为负 → IAE（参数防线）")
    void negativeWeight_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FeasibleCandidatePicker.pick(
                List.of(nearSlow()), WIND, PAYLOAD, PARAMS, NOW + 300_000L, -0.5, NOW));
    }

    @Test
    @DisplayName("巡航速度未登记（NaN）的组合延误视为 +∞，紧死线下恒输")
    void unregisteredCruise_losesUnderDeadline() {
        CandidateGenerator.CandidateCombo noCruise = new CandidateGenerator.CandidateCombo(
                "CMB-NOCRUISE", "UNKNOWN", "NEST-03", "UAV-03", 1.0, 90.0, 12.0, Double.NaN);
        long deadline = NOW + 300_000L;
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(noCruise, farFast()), WIND, PAYLOAD, PARAMS, deadline, 1.0, NOW);

        assertEquals("CMB-FAR", s.chosen().comboId(),
                "未登记巡航速度的组合在延误维度恒排最后（保守方向）");
    }

    @Test
    @DisplayName("空候选列表 → chosen 为 null（旧行为不变）")
    void emptyCombos_nullChosen() {
        FeasibleCandidatePicker.Selection s = FeasibleCandidatePicker.pick(
                List.of(), WIND, PAYLOAD, PARAMS, NOW + 300_000L, 1.0, NOW);

        assertNull(s.chosen());
    }
}
