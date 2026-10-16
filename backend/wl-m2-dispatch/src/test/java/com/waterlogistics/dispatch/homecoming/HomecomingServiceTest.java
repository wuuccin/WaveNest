package com.waterlogistics.dispatch.homecoming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.contract.rest.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模块层编排薄壳的完成判据：结论能原样透传给下游、占位请求只在第二级、
 * 第三级不臆造迫降点（本轮只留接口，不接 B 组精算）。
 */
class HomecomingServiceTest {

    private static final String HOME_NEST = "NEST-HOME";
    private static final GeoPoint POSITION = new GeoPoint(120.15, 30.28);
    private static final long SLOT_HOLD_MS = 2_000L;

    private final HomecomingService service = new HomecomingService();

    private static HomecomingInput input(double batteryPercent) {
        return new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1", HOME_NEST,
                POSITION, batteryPercent, 40.0, true, null, null, "LOW_BATTERY", 1_700_000_000_000L,
                Boolean.TRUE);
    }

    private static HomecomingParams params(Long arrivalEtaMs) {
        // 触发裕度 20（e10_reserve_ratio=0.20×100）、他巢系数 0.8、缓冲 0、未接 A5 兜底与迟滞退出
        return new HomecomingParams(20.0, 20.0, null, 0.8, 1.0, 0L, arrivalEtaMs, SLOT_HOLD_MS);
    }

    /** 第一级：编排层原样透传回原巢结论，不发占位请求 */
    @Test
    void firstTier_shouldPassThroughWithoutSlotRequest() {
        HomecomingDecision d = service.reviewAndDecide(input(80.0), params(1_000L), List.of());

        assertEquals(HomecomingTier.RETURN_HOME_NEST, d.tier());
        assertEquals(HOME_NEST, d.targetId());
        assertFalse(d.needSlotRequest());
        assertTrue(d.needsNoSlotRequest());
        assertNotNull(d.reason(), "结论要能落库审计");
    }

    /** 第二级：迫停他巢才发占位请求，并带上逐项打叉审计表 */
    @Test
    void secondTier_shouldCarryAuditAndSlotRequest() {
        List<NestCandidate> candidates = List.of(
                new NestCandidate("NEST-A", 2, List.of(), 45.0),
                new NestCandidate("NEST-B", 2, List.of(new TimeWindow(20_000L, 22_000L)), 25.0));

        HomecomingDecision d = service.reviewAndDecide(input(50.0), params(1_000L), candidates);

        assertEquals(HomecomingTier.FORCE_STOP_OTHER_NEST, d.tier());
        assertEquals("NEST-B", d.targetId());
        assertTrue(d.needSlotRequest(), "仅迫停他巢发 D 组占位请求");
        assertEquals(2, d.candidateAudit().size(), "审计表要能逐项展示候选打叉");
    }

    /** 第三级：判定层不得臆造迫降点，目标交 B 组精算 */
    @Test
    void thirdTier_shouldNotInventLandingPoint() {
        HomecomingDecision d = service.reviewAndDecide(input(30.0), params(1_000L), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
        assertNull(d.targetId(), "迫降点待 B 组精算或应急迫降点台账给出");
        assertNull(d.targetPosition());
        assertFalse(d.needSlotRequest(), "迫降野外不占位");
    }
}