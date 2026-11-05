package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PremiseMonitor} 单测（A 组 · {@code 80} §3.2.8 事实层 P0–P4 ＋ 统计层）。
 *
 * <p>重点覆盖清单中的 12／13／16／17 四条：持续扰动置位而瞬时抖动不置位、P4 与 INVALID 的
 * 语义区分、上行船不触发 P0、<b>中途掉头与方向参考锁定</b>。
 */
class PremiseMonitorTest {

    private static final long T0_MS = 1_700_000_000_000L;
    private static final double WATERWAY_COURSE_DEG = 0.0;

    private static PremiseMonitor.Params params() {
        return PremiseMonitor.Params.defaults();
    }

    /** 顺航道正向（dirRef0 = +1）的监测器，航向参考锁定为 0°。 */
    private static PremiseMonitor downbound() {
        return new PremiseMonitor(params(), 1, List.of()).lockCourseRef(WATERWAY_COURSE_DEG);
    }

    private static PremiseMonitor.Observation obs(double tSec, double s, double v, double cog,
            NavPhaseTracker.Phase phase, boolean ambiguous, boolean restricted) {
        return new PremiseMonitor.Observation(T0_MS + Math.round(tSec * 1000.0), s, v, cog,
                phase, ambiguous, restricted);
    }

    /** 平稳下行帧：s 前进、航向与航道一致、在航。 */
    private static PremiseMonitor.Observation normal(double tSec, boolean ambiguous,
            boolean restricted) {
        return obs(tSec, 1000.0 + 2.0 * tSec, 2.0, 0.0, NavPhaseTracker.Phase.UNDERWAY,
                ambiguous, restricted);
    }

    @Test
    @DisplayName("正常航行 → VALID / NONE")
    void normalOperation_shouldBeValid() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment assessment = monitor.observe(normal(0, false, false));

        assertEquals(PremiseMonitor.Verdict.VALID, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.NONE, assessment.reason());
        assertTrue(monitor.isInitialized());
        assertEquals(1, monitor.lockedDirectionSign());
    }

    @Test
    @DisplayName("P0 掉头：v_s 由正转负并稳定 tDirSec → INVALID(REVERSED)；同时 dCog 超阈")
    void p0SustainedReversal_shouldInvalidate() {
        PremiseMonitor monitor = downbound();
        for (int i = 0; i < 6; i++) {
            assertEquals(PremiseMonitor.Verdict.VALID,
                    monitor.observe(normal(i * 10.0, false, false)).verdict());
        }
        // 掉头：v_s 转负、cog 转 180°，需稳定 tDirSec=30 s
        PremiseMonitor.Assessment early = monitor.observe(
                obs(60, 1120.0, -2.0, 180.0, NavPhaseTracker.Phase.UNDERWAY, false, false));
        assertEquals(PremiseMonitor.Verdict.VALID, early.verdict(), "刚翻转、未满 tDirSec 不应置位");

        PremiseMonitor.Assessment later = monitor.observe(
                obs(95, 1120.0, -2.0, 180.0, NavPhaseTracker.Phase.UNDERWAY, false, false));
        assertEquals(PremiseMonitor.Verdict.INVALID, later.verdict());
        assertEquals(PremiseMonitor.Reason.REVERSED, later.reason());
        assertEquals(PremiseStatus.INVALID, later.verdict().toStatus());
    }

    @Test
    @DisplayName("P0 瞬时翻转（单帧）不置位——持续成立才触发")
    void p0TransientReversal_shouldNotInvalidate() {
        PremiseMonitor monitor = downbound();
        monitor.observe(normal(0, false, false));
        monitor.observe(obs(10, 1020.0, -2.0, 180.0, NavPhaseTracker.Phase.UNDERWAY, false, false));
        PremiseMonitor.Assessment assessment = monitor.observe(normal(20, false, false));

        assertEquals(PremiseMonitor.Verdict.VALID, assessment.verdict());
    }

    @Test
    @DisplayName("上行船（dirRef0 = −1）持续 v_s < 0：不触发 P0、不判异常")
    void upboundShip_shouldNotTriggerP0() {
        PremiseMonitor monitor = new PremiseMonitor(params(), -1, List.of())
                .lockCourseRef(WATERWAY_COURSE_DEG);

        PremiseMonitor.Assessment assessment = null;
        for (int i = 0; i < 30; i++) {
            // 上行：v_s = −2、cog = 180°（与锁定的 courseRef=180° 一致）
            assessment = monitor.observe(
                    obs(i * 10.0, 5000.0 - 2.0 * i * 10.0, -2.0, 180.0,
                            NavPhaseTracker.Phase.UNDERWAY, false, false));
        }
        assertEquals(PremiseMonitor.Verdict.VALID, assessment.verdict(),
                "上行是内河双向航道的正常工况，不得触发 P0");
    }

    @Test
    @DisplayName("P1 持续偏航（M-of-N ＋ 驻留）→ SUSPECT(DEVIATED)，且前提仍按 VALID 上报")
    void p1SustainedDeviation_shouldBecomeSuspect() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment assessment = null;
        for (int i = 0; i < 18; i++) {
            // 航向 90°、相对锁定参考 0° 的角差 90° > psiThr=20°
            assessment = monitor.observe(
                    obs(i * 10.0, 1000.0 + 2.0 * i * 10.0, 2.0, 90.0,
                            NavPhaseTracker.Phase.UNDERWAY, false, false));
        }
        assertEquals(PremiseMonitor.Verdict.SUSPECT, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.DEVIATED, assessment.reason());
        assertEquals(PremiseStatus.VALID, assessment.verdict().toStatus(),
                "偏航属量变，前提仍成立");
        assertTrue(assessment.verdict().contractCode().equals("SUSPECT"));
    }

    @Test
    @DisplayName("方向参考锁定：courseRef 不随实时方向翻转，否则真掉头测不出来")
    void courseRefMustStayLocked() {
        // 若 courseRef 跟着实时 cog 走，下面的 dCog 会恒为 0、永远测不出偏离
        PremiseMonitor monitor = downbound();
        monitor.observe(normal(0, false, false));

        PremiseMonitor.Assessment deviated = null;
        for (int i = 1; i <= 20; i++) {
            deviated = monitor.observe(
                    obs(i * 10.0, 1000.0 + 2.0 * i * 10.0, 2.0, 90.0,
                            NavPhaseTracker.Phase.UNDERWAY, false, false));
        }
        assertEquals(PremiseMonitor.Reason.DEVIATED, deviated.reason(),
                "锁定 0° 的 courseRef 应使 90° 航向被判为偏航");

        // 回到锁定航向，驻留期未满前不应继续保持 SUSPECT
        PremiseMonitor.Assessment back = null;
        for (int i = 21; i <= 24; i++) {
            back = monitor.observe(normal(i * 10.0, false, false));
        }
        assertEquals(PremiseMonitor.Verdict.VALID, back.verdict());
    }

    @Test
    @DisplayName("P2 停车（navPhase = 停车）→ INVALID(STOPPED)")
    void p2Stopped_shouldInvalidate() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment assessment = monitor.observe(
                obs(0, 1000.0, 0.05, 0.0, NavPhaseTracker.Phase.STOPPED, false, false));

        assertEquals(PremiseMonitor.Verdict.INVALID, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.STOPPED, assessment.reason());
    }

    @Test
    @DisplayName("P3 分汊歧义：落在邻域内且歧义持续 tBpSec → INVALID(BRANCH_AMBIGUOUS)")
    void p3BranchAmbiguous_shouldInvalidate() {
        PremiseMonitor monitor = new PremiseMonitor(params(), 1, List.of(1000.0))
                .lockCourseRef(WATERWAY_COURSE_DEG);

        PremiseMonitor.Assessment assessment = null;
        for (int i = 0; i < 8; i++) {
            assessment = monitor.observe(
                    obs(i * 10.0, 1000.0, 2.0, 0.0, NavPhaseTracker.Phase.UNDERWAY, true, false));
        }
        assertEquals(PremiseMonitor.Verdict.INVALID, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.BRANCH_AMBIGUOUS, assessment.reason());

        // 邻域外（s=2000 距分汊点 1000 m，超出 bpOut=300）即使歧义也不置位
        PremiseMonitor outside = new PremiseMonitor(params(), 1, List.of(1000.0))
                .lockCourseRef(WATERWAY_COURSE_DEG);
        PremiseMonitor.Assessment outsideAssessment = null;
        for (int i = 0; i < 8; i++) {
            outsideAssessment = outside.observe(
                    obs(i * 10.0, 2000.0, 2.0, 0.0, NavPhaseTracker.Phase.UNDERWAY, true, false));
        }
        assertEquals(PremiseMonitor.Verdict.VALID, outsideAssessment.verdict());
    }

    @Test
    @DisplayName("P4 受限水域 → NOT_APPLICABLE(RESTRICTED_AREA)，与 INVALID 语义分开")
    void p4RestrictedArea_shouldBeNotApplicable() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment assessment = null;
        for (int i = 0; i < 8; i++) {
            assessment = monitor.observe(normal(i * 10.0, false, true));
        }
        assertEquals(PremiseMonitor.Verdict.NOT_APPLICABLE, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.RESTRICTED_AREA, assessment.reason());
        assertEquals("NOT_APPLICABLE", assessment.verdict().contractCode());
        assertEquals(PremiseStatus.INVALID, assessment.verdict().toStatus(),
                "三值口径下有损映射为 INVALID");
    }

    @Test
    @DisplayName("优先级 P4 > P3：歧义 ＋ 受限同时成立时取 P4")
    void priority_p4OverP3() {
        PremiseMonitor monitor = new PremiseMonitor(params(), 1, List.of(1000.0))
                .lockCourseRef(WATERWAY_COURSE_DEG);

        PremiseMonitor.Assessment assessment = null;
        for (int i = 0; i < 8; i++) {
            assessment = monitor.observe(
                    obs(i * 10.0, 1000.0, 2.0, 0.0, NavPhaseTracker.Phase.UNDERWAY, true, true));
        }
        assertEquals(PremiseMonitor.Verdict.NOT_APPLICABLE, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.RESTRICTED_AREA, assessment.reason());
    }

    @Test
    @DisplayName("无航行阶段（数据不足）→ UNKNOWN(INSUFFICIENT_DATA)")
    void noNavPhase_shouldBeUnknown() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment assessment = monitor.observe(
                obs(0, 1000.0, 2.0, 0.0, null, false, false));

        assertEquals(PremiseMonitor.Verdict.UNKNOWN, assessment.verdict());
        assertEquals(PremiseMonitor.Reason.INSUFFICIENT_DATA, assessment.reason());
        assertEquals(PremiseStatus.UNKNOWN, assessment.verdict().toStatus());
    }

    @Test
    @DisplayName("统计层：zSeries 按 confirmWindow 截断，粗标记用 M-of-N（3/5）")
    void zSeriesAndCoarseFlag_shouldFollowMofN() {
        PremiseMonitor monitor = downbound();
        for (int i = 0; i < 3; i++) {
            monitor.addZSample(new PremiseMonitor.ZSample(2.0, 0.5, 0.5, 0.5));
        }
        for (int i = 0; i < 2; i++) {
            monitor.addZSample(new PremiseMonitor.ZSample(0.3, 0.2, 0.2, 0.2));
        }
        assertEquals(5, monitor.zSeries().size());
        assertTrue(monitor.coarseFlag(), "5 个样本中 3 个超 1σ，M-of-N(3/5) 应成立");

        PremiseMonitor sparse = downbound();
        sparse.addZSample(new PremiseMonitor.ZSample(2.0, 0.0, 0.0, 0.0));
        sparse.addZSample(new PremiseMonitor.ZSample(2.0, 0.0, 0.0, 0.0));
        sparse.addZSample(new PremiseMonitor.ZSample(0.1, 0.0, 0.0, 0.0));
        sparse.addZSample(new PremiseMonitor.ZSample(0.1, 0.0, 0.0, 0.0));
        sparse.addZSample(new PremiseMonitor.ZSample(0.1, 0.0, 0.0, 0.0));
        assertFalse(sparse.coarseFlag(), "只有 2 个超限，不得触发粗标记");

        PremiseMonitor capped = downbound();
        for (int i = 0; i < 9; i++) {
            capped.addZSample(new PremiseMonitor.ZSample(i, 0.0, 0.0, 0.0));
        }
        assertEquals(5, capped.zSeries().size(), "zSeries 长度受 confirmWindow 约束");
        assertEquals(4.0, capped.zSeries().get(0).zAlong(), 1e-12, "保留的应是最近的 5 个");
    }

    @Test
    @DisplayName("zSample 归一化：按 σ 与阈值折算，σ 为 0 时不除零")
    void zSampleNormalization_shouldBeSafe() {
        PremiseMonitor.ZSample sample =
                PremiseMonitor.zSample(-30.0, 20.0, 15.0, 10.0, 8.0, 8.0, 0.25, 0.25);
        assertEquals(1.5, sample.zAlong(), 1e-12, "|Δs|/σ_along");
        assertEquals(1.5, sample.zCross(), 1e-12, "xte/σ_cross");
        assertEquals(1.0, sample.zCog(), 1e-12, "|ΔCOG|/sigmaCog");
        assertEquals(1.0, sample.zRho(), 1e-12, "ρ/rhoTh");
        assertEquals(1.5, sample.maxAbs(), 1e-12);

        PremiseMonitor.ZSample zeroSigma =
                PremiseMonitor.zSample(10.0, 0.0, 10.0, 0.0, 10.0, 0.0, 0.1, 0.0);
        assertEquals(0.0, zeroSigma.maxAbs(), 1e-12);
    }

    @Test
    @DisplayName("需要重新获取预测的粗门槛：INVALID 或粗标记都算")
    void needsReacquire_shouldCoverInvalidAndCoarseFlag() {
        PremiseMonitor monitor = downbound();
        PremiseMonitor.Assessment invalid = monitor.observe(
                obs(0, 1000.0, 0.05, 0.0, NavPhaseTracker.Phase.STOPPED, false, false));
        assertTrue(invalid.needsReacquire());

        PremiseMonitor valid = downbound();
        assertFalse(valid.observe(normal(0, false, false)).needsReacquire());
    }

    @Test
    @DisplayName("构造与参数校验：方向符号只能 ±1、M-of-N 与阈值必须合法")
    void validation_shouldRejectBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new PremiseMonitor(params(), 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PremiseMonitor.Params(0.5, 30.0, 20.0, 120.0, 8.0, 6, 5,
                        300.0, 300.0, 60.0, 60.0, 1.0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new PremiseMonitor.Params(0.5, 30.0, 20.0, 120.0, 8.0, 3, 5,
                        300.0, 300.0, 60.0, 60.0, 0.0, 5));
    }
}
