package com.waterlogistics.dispatch.frame;

import com.waterlogistics.contract.ws.CandidateView;
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
     * @param chosenNestId     中选机巢；未选出时为 {@code null}
     * @param chosenDroneId    中选无人机；未选出时为 {@code null}
     * @param candidates       全量候选视图（含中选者）；传 {@code null} 按空列表处理
     */
    public record Input(
            String orderId,
            double meetLon,
            double meetLat,
            boolean locked,
            int candidateCount,
            List<String> rejectionReasons,
            LocalDateTime meetTime,
            String chosenNestId,
            String chosenDroneId,
            List<CandidateView> candidates) {

        /**
         * 兼容构造器：保留扩字段前的七参签名，候选对比三项留空（队列侧与旧测试仍可用）。
         */
        public Input(String orderId, double meetLon, double meetLat, boolean locked,
                     int candidateCount, List<String> rejectionReasons,
                     LocalDateTime meetTime) {
            this(orderId, meetLon, meetLat, locked, candidateCount, rejectionReasons,
                    meetTime, null, null, null);
        }
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
                toEpochMs(input.meetTime()),
                input.chosenNestId(),
                input.chosenDroneId(),
                List.copyOf(input.candidates() == null ? List.of() : input.candidates()));
    }

    /**
     * 落选候选的原因逐条成列（{@code 机型（机号）：原因}），与编排器日志/异常文案共用同一口径。
     *
     * <p>返回的是活列表，仅供立即消费（拼接文案）；要放进帧必须经过 {@link List#copyOf} 防御性拷贝，
     * 否则候选链后续改动会污染已经发出去的帧。
     */
    public static List<String> reasonsOf(FeasibleSelection selection) {
        // 与 candidateViewsOf 对称（Open CodeReview）：null 返回空列表而非 NPE，
        // 两个姊妹方法对同一入参类型必须同一契约——"不发帧的调用点不炸"
        if (selection == null) {
            return List.of();
        }
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

    /**
     * 全量候选逐条成视图（10-29 候选可观测性第二步）：把实现层的 {@link CandidateVerdict}
     * 映射成契约层的 {@link CandidateView}，供前端画候选对比卡。
     *
     * <p><b>含中选者，不只列落选</b>：中选者 {@code selected=true}，前端据此高亮；
     * 落选者 {@code selected=false} 且带原因，前端据此置灰。<b>与 {@link #reasonsOf} 不是替代关系</b>——
     * {@code reasonsOf} 只有落选项的文案串（前端第一步已在消费），本方法给的是全量结构化列表。
     *
     * <p><b>非有限值处理（评审 MEDIUM.19）</b>：上游组合缺失时判定端写的是 {@code Double.NaN}，
     * 而 NaN 不是合法 JSON、会让前端整帧解析失败，故此处转成 {@code null} 出库。
     * <b>{@code ±Infinity} 同理</b>——{@code JSON.stringify(Infinity)} 得到 {@code null}
     * 或直接抛错，Jackson 结构化序列化也会炸，故统一用 {@link Double#isFinite} 一并拦下；
     * 只判 {@code isNaN} 会让无穷大漏进帧里。
     *
     * @param selection      候选判定结果；传 {@code null} 返回空列表（不发帧的调用点不 NPE）
     * @param chosenComboId  中选组合标识；传 {@code null} 则无人被标中选
     * @return 不可变列表，顺序与判定端一致
     */
    public static List<CandidateView> candidateViewsOf(FeasibleSelection selection,
                                                       String chosenComboId) {
        if (selection == null) {
            return List.of();
        }
        List<CandidateView> views = new ArrayList<>();
        for (CandidateVerdict verdict : selection.candidates()) {
            double distKm = verdict.distKm();
            views.add(new CandidateView(
                    verdict.comboId(),
                    verdict.model(),
                    verdict.nestId(),
                    verdict.droneId(),
                    Double.isFinite(distKm) ? distKm : null,
                    verdict.feasible(),
                    verdict.reasons(),
                    chosenComboId != null && chosenComboId.equals(verdict.comboId())));
        }
        return List.copyOf(views);
    }

    private static Long toEpochMs(LocalDateTime time) {
        return time == null ? null : time.atZone(DB_ZONE).toInstant().toEpochMilli();
    }
}
