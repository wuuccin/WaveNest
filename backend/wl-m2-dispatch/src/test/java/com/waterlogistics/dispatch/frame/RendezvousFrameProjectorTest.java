package com.waterlogistics.dispatch.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.algo.candidate.FeasibilityJudge.Verdict;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.CandidateVerdict;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.FeasibleSelection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RendezvousFrameProjectorTest {

    private static final LocalDateTime MEET = LocalDateTime.of(2026, 10, 27, 14, 30, 0);

    @Test
    void project_shouldReportCandidateCountAndReasons() {
        FeasibleSelection selection = new FeasibleSelection(null, List.of(
                feasible("M1", "UAV-01"),
                infeasible("M2", "UAV-02", "剩余里程不足", "空域冲突"),
                infeasible("M3", "UAV-03", "机型不兼容")));

        RendezvousFrame frame = new RendezvousFrameProjector()
                .project(new RendezvousFrameProjector.Input(
                        "ORD-1", 115.9, 29.7, true, selection, MEET));

        // 判据 1：候选数 > 0 且等于本轮候选数
        assertTrue(frame.candidateCount() > 0);
        assertEquals(3, frame.candidateCount());

        // 判据 2：淘汰原因非空，且是不可变列表（防御性拷贝，外部改不动）
        assertEquals(2, frame.rejectionReasons().size());
        assertThrows(UnsupportedOperationException.class,
                () -> frame.rejectionReasons().add("外部篡改"));
    }

    @Test
    void project_etaTsShouldBeMeetTimeInAsiaShanghai() {
        FeasibleSelection selection = new FeasibleSelection(
                null, List.of(feasible("M1", "UAV-01")));

        RendezvousFrame frame = new RendezvousFrameProjector()
                .project(new RendezvousFrameProjector.Input(
                        "ORD-1", 115.9, 29.7, true, selection, MEET));

        long expected = MEET.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        assertEquals(expected, frame.etaTs().longValue());
    }

    private static CandidateVerdict feasible(String model, String droneId) {
        return new CandidateVerdict("C-" + droneId, model, "N1", droneId, Verdict.FEASIBLE, List.of());
    }

    private static CandidateVerdict infeasible(String model, String droneId, String... reasons) {
        return new CandidateVerdict("C-" + droneId, model, "N1", droneId,
                Verdict.INFEASIBLE, List.of(reasons));
    }
}
