package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NeesCalibrator} 单测（A 组 · {@code 80} §5.1 标定因子 {@code c}）。
 *
 * <p>重点：{@code NEES} 的定义、{@code c = m/2}、样本量决定的 95% 区间宽度（{@code 1.96/√N}）、
 * 无样本时回落到中性 1.0，以及验收门槛（{@code N ≥ 200} 且宽度 ≤ 0.15）。
 */
class NeesCalibratorTest {

    @Test
    @DisplayName("NEES 定义：(Δs/σ_along)² + (Δe/σ_cross)²")
    void nees_shouldSumSquaredNormalizedInnovations() {
        assertEquals(4.0, NeesCalibrator.nees(20.0, 0.0, 10.0, 10.0), 1e-12);
        assertEquals(4.0, NeesCalibrator.nees(0.0, -20.0, 10.0, 10.0), 1e-12);
        assertEquals(2.0, NeesCalibrator.nees(10.0, 10.0, 10.0, 10.0), 1e-12);
        assertEquals(0.0, NeesCalibrator.nees(0.0, 0.0, 10.0, 10.0), 1e-12);
    }

    @Test
    @DisplayName("无样本：c 回落到中性 1.0，且不满足验收")
    void noSamples_shouldFallBackToNeutral() {
        NeesCalibrator calibrator = new NeesCalibrator();
        NeesCalibrator.Result result = calibrator.result();

        assertEquals(0, result.sampleCount());
        assertEquals(1.0, result.calibrationFactor(), 1e-12);
        assertEquals(1.0, calibrator.calibrationFactor(), 1e-12);
        assertFalse(result.acceptable());
    }

    @Test
    @DisplayName("标定良好（E[NEES] = 2）→ c ≈ 1，N = 200 时满足验收")
    void wellCalibratedBand_shouldGiveUnitFactor() {
        NeesCalibrator calibrator = new NeesCalibrator();
        for (int i = 0; i < 200; i++) {
            calibrator.addNees(2.0);
        }
        NeesCalibrator.Result result = calibrator.result();

        assertEquals(2.0, result.meanNees(), 1e-12);
        assertEquals(1.0, result.calibrationFactor(), 1e-12);
        assertEquals(1.96 / Math.sqrt(200), result.confidenceWidth(), 1e-12);
        assertTrue(result.confidenceWidth() <= 0.15, "N=200 时宽度约 0.139，应满足 ≤ 0.15");
        assertTrue(result.acceptable());
    }

    @Test
    @DisplayName("带宽偏保守（E[NEES] = 1）→ c = 0.5，下游按 NIS/c 放大")
    void overConservativeBand_shouldGiveFactorBelowOne() {
        NeesCalibrator calibrator = new NeesCalibrator();
        for (int i = 0; i < 300; i++) {
            calibrator.addNees(1.0);
        }
        assertEquals(0.5, calibrator.calibrationFactor(), 1e-12);
    }

    @Test
    @DisplayName("带宽偏乐观（E[NEES] = 3）→ c = 1.5")
    void overOptimisticBand_shouldGiveFactorAboveOne() {
        NeesCalibrator calibrator = new NeesCalibrator();
        for (int i = 0; i < 300; i++) {
            calibrator.addNees(3.0);
        }
        assertEquals(1.5, calibrator.calibrationFactor(), 1e-12);
    }

    @Test
    @DisplayName("样本量不足：N = 100 时宽度 0.196 > 0.15，不满足验收")
    void insufficientSamples_shouldNotBeAcceptable() {
        NeesCalibrator calibrator = new NeesCalibrator();
        for (int i = 0; i < 100; i++) {
            calibrator.addNees(2.0);
        }
        NeesCalibrator.Result result = calibrator.result();

        assertTrue(result.confidenceWidth() > 0.15);
        assertFalse(result.acceptable());
    }

    @Test
    @DisplayName("由新息累计：add(Δs, Δe, σ_along, σ_cross) 与 addNees 等价")
    void addFromInnovations_shouldMatchAddNees() {
        NeesCalibrator fromInnovations = new NeesCalibrator();
        NeesCalibrator fromNees = new NeesCalibrator();
        fromInnovations.add(15.0, -5.0, 10.0, 10.0);
        fromNees.addNees(2.5);

        assertEquals(fromNees.calibrationFactor(), fromInnovations.calibrationFactor(), 1e-12);
        assertEquals(1, fromInnovations.sampleCount());
    }

    @Test
    @DisplayName("reset 后回到中性")
    void reset_shouldClearSamples() {
        NeesCalibrator calibrator = new NeesCalibrator();
        for (int i = 0; i < 200; i++) {
            calibrator.addNees(3.0);
        }
        assertEquals(1.5, calibrator.calibrationFactor(), 1e-12);

        calibrator.reset();
        assertEquals(0, calibrator.sampleCount());
        assertEquals(1.0, calibrator.calibrationFactor(), 1e-12);
    }

    @Test
    @DisplayName("非法输入：负 NEES、非正 σ 都要拒绝")
    void validation_shouldRejectBadArguments() {
        NeesCalibrator calibrator = new NeesCalibrator();
        assertThrows(IllegalArgumentException.class, () -> calibrator.addNees(-1.0));
        assertThrows(IllegalArgumentException.class, () -> calibrator.addNees(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> NeesCalibrator.nees(1.0, 1.0, 0.0, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> NeesCalibrator.nees(1.0, 1.0, 10.0, -1.0));
    }
}
