package com.waterlogistics.dispatch.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.candidate.FeasibilityJudge.Verdict;
import com.waterlogistics.contract.ws.CandidateView;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.CandidateVerdict;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.FeasibleSelection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RendezvousFrameProjectorTest {

    private static final LocalDateTime MEET = LocalDateTime.of(2026, 10, 27, 14, 30, 0);

    @Test
    void project_shouldCarryCandidateCountAndReasons() {
        // 故意传活列表：调用方后续改动它，不得污染已经发出去的帧
        List<String> reasons = new ArrayList<>(List.of("M2（UAV-02）：剩余里程不足；空域冲突"));

        RendezvousFrame frame = new RendezvousFrameProjector()
                .project(new RendezvousFrameProjector.Input(
                        "ORD-1", 115.9, 29.7, true, 3, reasons, MEET));

        // 判据 1：候选数 > 0，且等于本轮候选数
        assertTrue(frame.candidateCount() > 0);
        assertEquals(3, frame.candidateCount());

        // 判据 2：淘汰原因非空，且是不可变列表（防御性拷贝，外部改不动）
        assertEquals(1, frame.rejectionReasons().size());
        assertThrows(UnsupportedOperationException.class,
                () -> frame.rejectionReasons().add("外部篡改"));
        reasons.add("候选链后续新增的落选原因");
        assertEquals(1, frame.rejectionReasons().size());
    }

    @Test
    void project_etaTsShouldBeMeetTimeInAsiaShanghai() {
        RendezvousFrame frame = new RendezvousFrameProjector()
                .project(new RendezvousFrameProjector.Input(
                        "ORD-1", 115.9, 29.7, true, 3,
                        List.of("M2（UAV-02）：剩余里程不足"), MEET));

        long expected = MEET.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        assertEquals(expected, frame.etaTs().longValue());
    }

    /**
     * 候选对比三项（10-29 第二步）：中选机巢／中选无人机／全量候选必须随帧下发，
     * 且<b>全量候选含中选者</b>——前端按 {@code selected} 高亮，缺中选者则对比卡无高亮项。
     */
    @Test
    void project_shouldCarryChosenNestAndFullCandidates() {
        FeasibleSelection selection = new FeasibleSelection(null, List.of(
                new CandidateVerdict("C-A", "M400", "NEST-01", "UAV-02", 4.57,
                        Verdict.FEASIBLE, List.of()),
                new CandidateVerdict("C-B", "M30T", "NEST-02", "UAV-03", 2.19,
                        Verdict.FEASIBLE, List.of()),
                new CandidateVerdict("C-C", "M3T", "NEST-02", "UAV-04", 2.19,
                        Verdict.INFEASIBLE, List.of("载重不足"))));

        List<CandidateView> views =
                RendezvousFrameProjector.candidateViewsOf(selection, "C-A");

        RendezvousFrame frame = new RendezvousFrameProjector()
                .project(new RendezvousFrameProjector.Input(
                        "ORD-1", 115.9, 29.7, true, 3, List.of(), MEET,
                        "NEST-01", "UAV-02", views));

        // 判据 1：中选机巢与中选无人机随帧下发
        assertEquals("NEST-01", frame.chosenNestId());
        assertEquals("UAV-02", frame.chosenDroneId());

        // 判据 2：全量候选（含中选者），不是只列落选
        assertEquals(3, frame.candidates().size());
        assertEquals(1, frame.candidates().stream().filter(CandidateView::selected).count());
        assertEquals("C-A", frame.candidates().stream()
                .filter(CandidateView::selected).findFirst().orElseThrow().candidateId());

        // 判据 3：可行布尔 + 距离透传；落选项带原因
        assertTrue(frame.candidates().get(0).feasible());
        assertEquals(4.57, frame.candidates().get(0).distKm(), 1e-6);
        assertEquals(List.of("载重不足"), frame.candidates().get(2).reasons());
        assertEquals(false, frame.candidates().get(2).feasible());

        // 判据 4：候选列表不可变（防御性拷贝，外部改不动）
        assertThrows(UnsupportedOperationException.class,
                () -> frame.candidates().add(frame.candidates().get(0)));
    }

    /** 上游组合缺失时 distKm 为 NaN，必须转成 null 出库——NaN 不是合法 JSON，会让前端整帧解析失败。 */
    @Test
    void candidateViewsOf_shouldTurnNanDistKmIntoNull() {
        FeasibleSelection selection = new FeasibleSelection(null, List.of(
                new CandidateVerdict("C-A", "M400", "NEST-01", "UAV-02", Double.NaN,
                        Verdict.FEASIBLE, List.of())));

        List<CandidateView> views =
                RendezvousFrameProjector.candidateViewsOf(selection, "C-A");

        assertEquals(null, views.get(0).distKm());
    }

    /** 淘汰原因文案口径由投影器统一提供，编排器的帧与异常消息都走它，两边不得分叉。 */
    @Test
    void reasonsOf_shouldFormatRejectedCandidatesInSharedWording() {
        FeasibleSelection selection = new FeasibleSelection(null, List.of(
                feasible("M1", "UAV-01"),
                infeasible("M2", "UAV-02", "剩余里程不足", "空域冲突")));

        assertEquals(List.of("M2（UAV-02）：剩余里程不足；空域冲突"),
                RendezvousFrameProjector.reasonsOf(selection));
    }

    private static CandidateVerdict feasible(String model, String droneId) {
        return new CandidateVerdict("C-" + droneId, model, "N1", droneId, 3.5, Verdict.FEASIBLE, List.of());
    }

    private static CandidateVerdict infeasible(String model, String droneId, String... reasons) {
        return new CandidateVerdict("C-" + droneId, model, "N1", droneId, 2.8,
                Verdict.INFEASIBLE, List.of(reasons));
    }
}
