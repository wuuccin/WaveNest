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
 * 也便于单测。输出不可变帧。
 *
 * <p><b>输入是数据而不是候选链对象</b>：{@link Input} 只带求解结果（会面坐标 ＋ 候选数）、
 * 淘汰原因与会面时刻，<b>不要求调用方持有候选判定</b>——编排器（T1-2）与队列侧（T1-6）
 * 都能构造，故两侧可以共用这一份投影口径，不会各写一套、信号分叉。
 *
 * <p><b>etaTs 按 {@code meet_time} 口径</b>：取会面时刻（{@code MeetPlan.takeoffTime()}），
 * 该字段名沿用早期命名，其值来自求解结果的 {@code meetTMin}（即会面时刻），并非起飞时刻；
 * 真起飞时刻由 {@code FlightPhaseTimeline} 另算（会面时刻 − 去程时长）。
 * 时区与 {@code TaskQueueService}、{@code FlightPhaseAdvancer} 一致
 * （DB serverTimezone = Asia/Shanghai），不用 JVM 默认时区。
 */
public final class RendezvousFrameProjector {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 投影输入。
     *
     * @param orderId          订单号
     * @param meetLon          会面点经度
     * @param meetLat          会面点纬度
     * @param locked           会面点是否锁定
     * @param candidateCount   本轮候选数
     * @param rejectionReasons 逐条淘汰原因；传 {@code null} 按空列表处理（不产出原因，也不让下游 NPE）
     * @param meetTime         会面时刻（LocalDateTime）
     */
    public record Input(
            String orderId,
            double meetLon,
            double meetLat,
            boolean locked,
            int candidateCount,
            List<String> rejectionReasons,
            LocalDateTime meetTime) {
    }

    public RendezvousFrame project(Input input) {
        return new RendezvousFrame(
                input.orderId(),
                input.meetLon(),
                input.meetLat(),
                input.locked(),
                input.candidateCount(),
                // 防御性拷贝：调用方手里的活列表后续被改动，不得污染已经发出去的帧
                List.copyOf(input.rejectionReasons() == null
                        ? List.of()
                        : input.rejectionReasons()),
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
