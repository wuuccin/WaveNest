package com.waterlogistics.algo.inflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.EmergencyLandingSelector.EmergencyLandingSelection;
import com.waterlogistics.contract.rest.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第三级迫降点选择器单测（11-16 任务清单 1-6／2-2）：
 * ① 水面／陆地二值；② 距航道半宽阈值；③ 字段缺失／阈值未注入按不可算淘汰；④ 无可用点合法退化。
 */
class EmergencyLandingSelectorTest {

    private static final double HALF_WIDTH_M = 500.0;

    private static EmergencyLandingCandidate candidate(String id, double distM, String surface, boolean available) {
        return new EmergencyLandingCandidate(id, new GeoPoint(115.85, 28.65), distM, surface, available);
    }

    /** 水面且垂距在半宽内 → 选中 */
    @Test
    void waterInsideCorridor_shouldBeSelected() {
        EmergencyLandingSelection r = EmergencyLandingSelector.select(
                List.of(candidate("ELP-1", 300.0, EmergencyLandingCandidate.SURFACE_WATER, true)),
                HALF_WIDTH_M);
        assertTrue(r.hasSelection());
        assertEquals("ELP-1", r.elpId());
        assertTrue(r.position() != null);
        assertTrue(r.audit().isEmpty());
    }

    /** 垂距超半宽 → 否决 OUTSIDE_CORRIDOR */
    @Test
    void outsideCorridor_shouldBeRejected() {
        EmergencyLandingSelection r = EmergencyLandingSelector.select(
                List.of(candidate("ELP-FAR", 600.0, EmergencyLandingCandidate.SURFACE_WATER, true)),
                HALF_WIDTH_M);
        assertFalse(r.hasSelection());
        assertEquals(1, r.audit().size());
        assertEquals(EmergencyLandingSelector.REJECT_OUTSIDE_CORRIDOR, r.audit().get(0).reason());
    }

    /** 陆地 → 否决 ON_LAND（水面／陆地二值，不默认水面） */
    @Test
    void onLand_shouldBeRejected() {
        EmergencyLandingSelection r = EmergencyLandingSelector.select(
                List.of(candidate("ELP-LAND", 100.0, EmergencyLandingCandidate.SURFACE_LAND, true)),
                HALF_WIDTH_M);
        assertFalse(r.hasSelection());
        assertEquals(EmergencyLandingSelector.REJECT_ON_LAND, r.audit().get(0).reason());
    }

    /** 台账不可用 → 否决 NOT_AVAILABLE，优先于几何判定 */
    @Test
    void unavailable_shouldBeRejectedFirst() {
        EmergencyLandingSelection r = EmergencyLandingSelector.select(
                List.of(candidate("ELP-BUSY", 600.0, EmergencyLandingCandidate.SURFACE_LAND, false)),
                HALF_WIDTH_M);
        assertFalse(r.hasSelection());
        assertEquals(EmergencyLandingSelector.REJECT_NOT_AVAILABLE, r.audit().get(0).reason());
    }

    /** 字段缺失或阈值未注入 → 不可算淘汰，不默认水面／不取 0 */
    @Test
    void missingFieldOrThreshold_shouldBeUncomputable() {
        EmergencyLandingCandidate noDist = new EmergencyLandingCandidate("ELP-NODIST",
                new GeoPoint(115.85, 28.65), null, EmergencyLandingCandidate.SURFACE_WATER, true);
        EmergencyLandingCandidate noSurface = new EmergencyLandingCandidate("ELP-NOSURF",
                new GeoPoint(115.85, 28.65), 100.0, null, true);
        EmergencyLandingCandidate good = candidate("ELP-GOOD", 100.0,
                EmergencyLandingCandidate.SURFACE_WATER, true);

        EmergencyLandingSelection r = EmergencyLandingSelector.select(List.of(noDist, noSurface, good), HALF_WIDTH_M);
        assertTrue(r.hasSelection(), "前两个不可算淘汰，第三个可用仍应选中");
        assertEquals("ELP-GOOD", r.elpId());
        assertEquals(2, r.audit().size());
        assertEquals(EmergencyLandingSelector.REJECT_UNCOMPUTABLE, r.audit().get(0).reason());
        assertEquals(EmergencyLandingSelector.REJECT_UNCOMPUTABLE, r.audit().get(1).reason());

        EmergencyLandingSelection noThreshold = EmergencyLandingSelector.select(List.of(good), null);
        assertFalse(noThreshold.hasSelection(), "阈值未注入时不得取建议值冒充");
        assertEquals(EmergencyLandingSelector.REJECT_UNCOMPUTABLE, noThreshold.audit().get(0).reason());
    }

    /** 候选列表为空 / null → 合法无选中，逐项审计为空 */
    @Test
    void noCandidates_shouldReturnEmptySelection() {
        assertFalse(EmergencyLandingSelector.select(null, HALF_WIDTH_M).hasSelection());
        EmergencyLandingSelection empty = EmergencyLandingSelector.select(List.of(), HALF_WIDTH_M);
        assertFalse(empty.hasSelection());
        assertNull(empty.elpId());
        assertTrue(empty.audit().isEmpty());
    }

    /** 建议值常量按 1-6 裁定为 500 m */
    @Test
    void suggestedHalfWidth_shouldMatchDecision() {
        assertEquals(500.0, HomecomingParams.SUGGESTED_LANDING_CORRIDOR_HALF_WIDTH_M);
    }
}
