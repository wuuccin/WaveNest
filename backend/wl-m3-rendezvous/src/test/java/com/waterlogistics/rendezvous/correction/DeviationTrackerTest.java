package com.waterlogistics.rendezvous.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 去程段纠偏循环体单测（《84》§2.1 船侧 XTE 走廊退化版）。
 *
 * <p><b>本类只判「前提是否成立」，不产纠偏指令</b>——这是《84》§3.6 硬约束 1 的要求，
 * 故单测只验前提成立性，不验任何修正量。
 */
class DeviationTrackerTest {

    /** 正东向预测航迹（120.0,30.0）→（120.01,30.0） */
    private static final List<Double> LONS = List.of(120.0, 120.01);

    private static final List<Double> LATS = List.of(30.0, 30.0);

    /** 船在走廊内：前提仍成立，继续原计划 */
    @Test
    void track_shouldHoldPremiseWhenInsideCorridor() {
        DeviationTracker.PremiseVerdict v = DeviationTracker.track(120.005, 30.0005, LONS, LATS, 150.0);

        assertTrue(v.premiseHolds(), "|XTE| 约 56 m ≤ 150 m，前提应成立：" + v.reason());
        assertEquals(150.0, v.xtlM(), 1e-9, "判定所用走廊半宽须原样带出，供追溯");
        assertTrue(v.reason().contains("走廊内"), "结论须点明在走廊内：" + v.reason());
    }

    /** 船偏出走廊：前提失效，须上报 M2 走 6a／6b */
    @Test
    void track_shouldInvalidatePremiseWhenOutsideCorridor() {
        DeviationTracker.PremiseVerdict v = DeviationTracker.track(120.005, 30.001, LONS, LATS, 50.0);

        assertFalse(v.premiseHolds(), "|XTE| 约 111 m > 50 m，前提应失效：" + v.reason());
        assertTrue(v.reason().contains("前提失效"), "结论须点明前提失效：" + v.reason());
    }

    /** 符号保留给下游配合 ΔCOG 判改向意图（《84》§2.1 缺点③要求交叉判） */
    @Test
    void track_shouldExposeSideOfTrack() {
        DeviationTracker.PremiseVerdict north = DeviationTracker.track(120.005, 30.001, LONS, LATS, 500.0);
        DeviationTracker.PremiseVerdict south = DeviationTracker.track(120.005, 29.999, LONS, LATS, 500.0);

        assertTrue(north.isLeftOfTrack(), "船偏在航迹左侧（面向东，左为北）");
        assertFalse(south.isLeftOfTrack(), "船偏在航迹右侧");
        assertTrue(north.signedXteM() > 0, "左侧为正：" + north.signedXteM());
        assertTrue(south.signedXteM() < 0, "右侧为负：" + south.signedXteM());
    }

    /** 走廊半宽非法须报错，不得让「永不失效」静默成立 */
    @Test
    void track_shouldRejectNonPositiveCorridor() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DeviationTracker.track(120.005, 30.0, LONS, LATS, 0.0));

        assertTrue(thrown.getMessage().contains("走廊半宽"),
                "须点明是走廊半宽：" + thrown.getMessage());
    }
}