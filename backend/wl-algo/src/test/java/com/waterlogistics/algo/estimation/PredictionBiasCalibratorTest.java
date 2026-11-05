package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionBiasCalibrator} 单测（A 组 · {@code 80} §3.2.10 步骤 H）。
 *
 * <p>重点：<b>符号校验</b>（预测系统性偏慢时要建议<b>上调</b>速度，清单第 18 条）、
 * <b>限幅</b>（单次 ≤ {@code maxAdjustRatio}、偏置 ≤ {@code maxVBiasMs}，清单第 15 条）、
 * 连续周期门槛与断一次即复位、船速分档必须带符号、层内样本不足不出建议。
 */
class PredictionBiasCalibratorTest {

    private static PredictionBiasCalibrator.Params params() {
        // 窗 30、层内最少 30、连续 5 周期；其余取附录 B 初值
        return new PredictionBiasCalibrator.Params(60.0, 30, new double[] {1.5, 3.5},
                30, 30, 5, 1.0, 1.5, 0.7, 0.10, 0.5, 0.15, 0.02);
    }

    private static PredictionBiasCalibrator.Sample sample(double tauSec, double vAlongMs,
            double errAlongM, double errCrossM, double sigmaAlongM, double sigmaCrossM) {
        return new PredictionBiasCalibrator.Sample(tauSec, vAlongMs, errAlongM, errCrossM,
                sigmaAlongM, sigmaCrossM);
    }

    /** 灌 {@code count} 个同质样本。 */
    private static void fill(PredictionBiasCalibrator calibrator, int count, double tauSec,
            double vAlongMs, double errAlongM, double errCrossM, double sigmaAlongM,
            double sigmaCrossM) {
        for (int i = 0; i < count; i++) {
            calibrator.addSample(sample(tauSec, vAlongMs, errAlongM, errCrossM, sigmaAlongM, sigmaCrossM));
        }
    }

    /** 跑 {@code cycles} 个校准周期，返回最后一个周期的建议。 */
    private static List<PredictionBiasCalibrator.Suggestion> run(PredictionBiasCalibrator calibrator,
            int cycles) {
        List<PredictionBiasCalibrator.Suggestion> last = List.of();
        for (int i = 0; i < cycles; i++) {
            last = calibrator.suggest();
        }
        return last;
    }

    private static PredictionBiasCalibrator.Suggestion ofKind(
            List<PredictionBiasCalibrator.Suggestion> suggestions, PredictionBiasCalibrator.Kind kind) {
        return suggestions.stream().filter(s -> s.kind() == kind).findFirst().orElse(null);
    }

    @Test
    @DisplayName("层内样本不足 calMinSamples：不出任何建议")
    void insufficientSamples_shouldYieldNoSuggestion() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 29, 60.0, 2.0, 12.0, 0.0, 10.0, 10.0);

        assertTrue(run(calibrator, 10).isEmpty());
    }

    @Test
    @DisplayName("符号校验：实际里程持续大于预测 → 速度偏置建议为【正】（上调预测速度）")
    void alongBias_shouldBePositiveWhenActualRunsAhead() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        // errAlong = s_est − s_pred = +12 m，τ̄ = 60 s、σ_along = 10 m ⇒ |μ| > kBias·σ 成立
        fill(calibrator, 30, 60.0, 2.0, 12.0, 10.0, 10.0, 10.0);

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS);
        assertTrue(suggestion != null, "应产出速度偏置建议");
        assertEquals(0.2, suggestion.suggestedValue(), 1e-9, "μ/τ̄ = 12/60 = 0.2 m/s");
        assertTrue(suggestion.suggestedValue() > 0.0, "实际更靠前 ⇒ 偏置必须为正");
        assertEquals(60.0, suggestion.representativeTauSec(), 1e-9);
        assertEquals(30, suggestion.sampleCount());
    }

    @Test
    @DisplayName("符号反向：实际里程持续小于预测 → 偏置为负（下调）")
    void alongBias_shouldBeNegativeWhenActualRunsBehind() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, -12.0, 10.0, 10.0, 10.0);

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS);
        assertTrue(suggestion != null);
        assertTrue(suggestion.suggestedValue() < 0.0, "实际更靠后 ⇒ 偏置必须为负");
        assertEquals(-0.2, suggestion.suggestedValue(), 1e-9);
    }

    @Test
    @DisplayName("偏置限幅：|μ/τ̄| 超过 maxVBiasMs 时夹到 0.5 m/s")
    void alongBias_shouldBeClamped() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, 57.0, 10.0, 20.0, 10.0);   // 57/60 = 0.95 > 0.5

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS);
        assertTrue(suggestion != null);
        assertEquals(0.5, suggestion.suggestedValue(), 1e-12);
    }

    @Test
    @DisplayName("连续 calPersistM 周期才出建议（防单次异常触发调整）")
    void persistence_shouldGateSuggestions() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, 12.0, 10.0, 10.0, 10.0);

        List<PredictionBiasCalibrator.Suggestion> fourth = run(calibrator, 4);
        assertTrue(ofKind(fourth, PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS) == null,
                "第 4 个周期还不够，不得出建议");

        List<PredictionBiasCalibrator.Suggestion> fifth = run(calibrator, 1);
        assertTrue(ofKind(fifth, PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS) != null,
                "第 5 个周期应出建议");
    }

    @Test
    @DisplayName("中途条件不成立即复位：再攒满 calPersistM 才能再出建议")
    void persistence_shouldResetWhenConditionBreaks() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, 12.0, 10.0, 10.0, 10.0);
        run(calibrator, 4);   // 计数到 4

        // 用 30 个无偏样本把窗口冲干净（calWindowN = 30），条件不再成立
        fill(calibrator, 30, 60.0, 2.0, 0.0, 10.0, 10.0, 10.0);
        List<PredictionBiasCalibrator.Suggestion> afterBreak = calibrator.suggest();
        assertTrue(ofKind(afterBreak, PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS) == null,
                "条件中断的周期不得出建议（且计数应复位）");

        // 重新灌回有偏样本，仍需连续 5 个周期
        fill(calibrator, 30, 60.0, 2.0, 12.0, 10.0, 10.0, 10.0);
        List<PredictionBiasCalibrator.Suggestion> fourth = run(calibrator, 4);
        assertTrue(ofKind(fourth, PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS) == null);
        List<PredictionBiasCalibrator.Suggestion> fifth = run(calibrator, 1);
        assertTrue(ofKind(fifth, PredictionBiasCalibrator.Kind.ALONG_SPEED_BIAS) != null);
    }

    @Test
    @DisplayName("横向带宽过窄（q > kBand）→ 放大 α_cross，单次调整 ≤ maxAdjustRatio")
    void alphaCross_widenWhenBandTooNarrow() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        // 归一化横向误差 = 20/10 = 2.0σ > kBand 1.5
        fill(calibrator, 30, 60.0, 2.0, 0.0, 20.0, 10.0, 10.0);

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALPHA_CROSS_WIDEN);
        assertTrue(suggestion != null, "带宽过窄应建议放大 α_cross");
        assertEquals(0.02, suggestion.currentValue(), 1e-12);
        // ratio = min(0.10, 2.0/1.5 − 1 = 0.333) = 0.10 ⇒ 0.02 × 1.1
        assertEquals(0.022, suggestion.suggestedValue(), 1e-12);
        assertTrue(suggestion.suggestedValue() / suggestion.currentValue() - 1.0
                <= params().maxAdjustRatio() + 1e-12, "单次调整不得超 maxAdjustRatio");
    }

    @Test
    @DisplayName("横向带宽过保守（q < kTight）→ 缩小 α_cross，单次调整 ≤ maxAdjustRatio")
    void alphaCross_shrinkWhenBandTooWide() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        // 归一化横向误差 = 5/10 = 0.5σ < kTight 0.7
        fill(calibrator, 30, 60.0, 2.0, 0.0, 5.0, 10.0, 10.0);

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALPHA_CROSS_SHRINK);
        assertTrue(suggestion != null, "带宽过保守应建议收紧 α_cross");
        assertEquals(0.018, suggestion.suggestedValue(), 1e-12, "0.02 × (1 − 0.10)");
        assertTrue(suggestion.suggestedValue() < suggestion.currentValue());
    }

    @Test
    @DisplayName("纵向带宽过窄 → 放大 α_along")
    void alphaAlong_widenWhenBandTooNarrow() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, 20.0, 10.0, 10.0, 10.0);

        PredictionBiasCalibrator.Suggestion suggestion = ofKind(
                run(calibrator, 5), PredictionBiasCalibrator.Kind.ALPHA_ALONG_WIDEN);
        assertTrue(suggestion != null);
        assertEquals(0.165, suggestion.suggestedValue(), 1e-12, "0.15 × (1 + 0.10)");
    }

    @Test
    @DisplayName("船速分档必须带符号：v_s = −2 落在中档，不挤进最低档")
    void speedBucketing_shouldUseAbsoluteSpeed() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, -2.0, 0.0, 0.0, 10.0, 10.0);

        // τ=60 → 时域桶 1；|−2| = 2 m/s 落在 [1.5, 3.5) → 船速桶 1
        assertEquals(30, calibrator.sampleCount(1, 1));
        assertEquals(0, calibrator.sampleCount(1, 0), "上行样本不得挤进最低档");
    }

    @Test
    @DisplayName("分层：不同预测时域进不同的桶")
    void buckets_shouldSeparateByHorizon() {
        PredictionBiasCalibrator calibrator = new PredictionBiasCalibrator(params());
        fill(calibrator, 30, 60.0, 2.0, 0.0, 0.0, 10.0, 10.0);
        fill(calibrator, 20, 600.0, 2.0, 0.0, 0.0, 10.0, 10.0);

        assertEquals(30, calibrator.sampleCount(1, 1));
        assertEquals(20, calibrator.sampleCount(10, 1));
        assertEquals(2, calibrator.bucketCount());
    }

    @Test
    @DisplayName("归一化 RMS 统计量：标定良好时约等于 1；p95 绝对值只作展示")
    void statistics_shouldBeSane() {
        List<PredictionBiasCalibrator.Sample> calibrated = List.of(
                sample(60, 2, 10, 10, 10, 10),
                sample(60, 2, -10, -10, 10, 10),
                sample(60, 2, 10, -10, 10, 10),
                sample(60, 2, -10, 10, 10, 10));
        assertEquals(1.0, PredictionBiasCalibrator.rmsNormalizedAlong(calibrated), 1e-12);
        assertEquals(1.0, PredictionBiasCalibrator.rmsNormalizedCross(calibrated), 1e-12);
        assertEquals(10.0, PredictionBiasCalibrator.p95AbsError(calibrated, true), 1e-12);
    }

    @Test
    @DisplayName("参数与样本校验")
    void validation_shouldRejectBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new PredictionBiasCalibrator.Params(60.0, 30, new double[] {3.5, 1.5},
                        200, 30, 5, 1.0, 1.5, 0.7, 0.10, 0.5, 0.15, 0.02));
        assertThrows(IllegalArgumentException.class,
                () -> new PredictionBiasCalibrator.Params(60.0, 30, new double[] {1.5, 3.5},
                        200, 30, 5, 1.0, 0.5, 0.7, 0.10, 0.5, 0.15, 0.02));
        assertThrows(IllegalArgumentException.class,
                () -> sample(0.0, 2.0, 0.0, 0.0, 10.0, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> sample(60.0, 2.0, 0.0, 0.0, 0.0, 10.0));
    }
}
