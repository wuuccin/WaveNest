package com.waterlogistics.dispatch.frame;

import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.CandidateVerdict;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner.FeasibleSelection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 会面帧投影：把「求解结果 + 淘汰原因 + 会面时刻」投影成 {@link RendezvousFrame}。
 *
 * <p><b>纯函数、不依赖 Spring</b>：直接 {@code new RendezvousFrameProjector()} 即可使用，
 * 也便于单测。输入用 {@link Input} record，输出不可变帧——
 * 编排器（T1-2）与队列侧（T1-6）共用同一份口径，避免各写一套、信号分叉。
 *
 * <p><b>etaTs 按 {@code meet_time} 口径</b>：取 {@code MeetPlan.takeoffTime()}，该字段名沿用早期命名，
 * 其值来自求解结果的 {@code meetTMin}（即会面时刻），并非起飞时刻；真起飞时刻由
 * {@code FlightPhaseTimeline} 另算（会面时刻 − 去程时长）。时区与 {@code TaskQueueService}、
 * {@code FlightPhaseAdvancer} 一致（DB serverTimezone = Asia/Shanghai）。
 */
public final class RendezvousFrameProjector {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 投影输入：订单号、会面坐标、是否锁定、候选链结果、会面时刻（LocalDateTime）。 */
    public record Input(
            String orderId,
            double meetLon,
            double meetLat,
            boolean locked,
            FeasibleSelection selection,
            LocalDateTime meetTime) {
    }

    public RendezvousFrame project(Input input) {
        return new RendezvousFrame(
                input.orderId(),
                input.meetLon(),
                input.meetLat(),
                input.locked(),
                input.selection().candidates().size(),
                List.copyOf(reasonsOf(input.selection())),
                toEpochMs(input.meetTime()));
    }

    /**
     * 落选候选的原因逐条成列（{@code 机型（机号）：原因}），与编排器日志/异常文案共用同一口径。
     *
     * <p>返回的是活列表，仅供立即消费（拼接文案）；要放进帧必须经过 {@link List#copyOf} 防御性拷贝，
     * 否则候选链后续改动会污染已经发出去的帧。
     */
    public static List<String> reasonsOf(FeasibleSelection selection) {
        List<String> reasons = new ArrayList<>();
        for (CandidateVerdict verdict : selection.candidates()) {
            if (verdict.feasible()) {
                continue;
            }
            reasons.add(verdict.model() + "（" + verdict.droneId() + "）："
                    + String.join("；", verdict.reasons()));
        }
        return reasons;
    }

    private static Long toEpochMs(LocalDateTime time) {
        return time == null ? null : time.atZone(DB_ZONE).toInstant().toEpochMilli();
    }
}
