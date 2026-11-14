package com.waterlogistics.dispatch.plan;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner;
import com.waterlogistics.dispatch.frame.RendezvousFrameProjector;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次派发编排器（C0 · 10-21 任务清单）——<b>组批之后「选机→会面→派发」的唯一串法</b>。
 *
 * <p><b>为什么建这个类</b>：B0 把订单推进到「调度中」后<b>没有驱动者</b>，
 * 主链上唯一含完整串法的是 {@code RescheduleService.reschedule()}（重派场景，
 * 带 {@code requeue}）。本类把其中「选机→会面→派发→回填会面点」抽成共用段，
 * 供两条链复用：
 * <ul>
 *   <li><b>首次派发</b>：{@code DispatchTrigger} 在受理（{@code accept}）后续接本编排——
 *       <b>不走 {@code requeue}</b>（{@code requeue} 会清 {@code droneId}/{@code meetTime}
 *       并写「重入队」留痕，语义不符）；</li>
 *   <li><b>重派</b>：{@code RescheduleService.reschedule()} 先 {@code requeue} 再调本编排。</li>
 * </ul>
 *
 * <p><b>步骤与顺序（组批在前，勿调换）</b>：
 * <ol>
 *   <li>{@link TaskQueueService#startDispatch} 组批：待调度 → 调度中，
 *       <b>B3 闸门检查内含</b>——熔断时转「已受理待唤醒」，本编排就此结束（返回 {@code null}），
 *       不选机、不求解（熔断期间做无用功事小，无快照环境下空跑求解会把
 *       「延后」误变成「失败」）；</li>
 *   <li>{@link CandidateFeasibilityPlanner#selectFeasibleWithReasons} 选机（<b>C1 已接入 10-21</b>：
 *       生成 → 逐个判可行性 → 取最近的可飞候选；原为「只选不判」的
 *       {@code CandidateSelector#selectBest}）。<b>E3（10-23）</b>起改用带淘汰原因的返回结构，
 *       落选机型与原因随结果透出，判定口径不变（与 {@code selectFeasible} 共用同一
 *       {@code evaluate} 管线）；</li>
 *   <li>{@link RendezvousCoordinator#solveFor} 会面求解，判「追得上」；</li>
 *   <li>{@link TaskQueueService#dispatch} 派发：调度中 → 已派发（回填 {@code droneId}/{@code meetTime}）；</li>
 *   <li>会面点坐标回填 {@code order_task.meet_lon/meet_lat}（《22-界面契约》§5 组合级产物，须可回查）。</li>
 * </ol>
 *
 * <p><b>失败语义</b>：无候选／会面无解／状态机违例一律抛 {@link IllegalStateException}，
 * 由调用方所在事务回滚——「调度中」不会成为脏状态停留（组批流转一并回滚），
 * 故不再需要「求解必须先于状态流转」的旧顺序（旧顺序是为防无事务保护时的回退残留）。
 *
 * <p><b>纪律</b>：本类只做编排，状态流转委托 {@code queue}、选机委托 {@code candidate}、
 * 会面委托 {@code rendezvous}，不自带业务判据。
 */
@Service
public class DispatchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DispatchOrchestrator.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final TaskQueueService taskQueueService;
    private final RendezvousCoordinator rendezvous;
    private final OrderTaskMapper orderTaskMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MissionTrackAssembler missionTrackAssembler;
    private final TaskLogRecorder taskLogRecorder;
    private final DispatchPlanVerifier planVerifier;
    private final PlanTrackWriter planTrackWriter;
    private final OccupancySlotService occupancySlotService;

    /**
     * C1：选机段的可行性择优（卢肄文）。生成 → 逐个判可行性 → 取最近的可飞候选。
     *
     * <p>取代原先单点调用 {@code candidateSelector.selectBest}（只选不判）——
     * 见本类第 2 步与 {@link CandidateFeasibilityPlanner} 的类注释。
     */
    private final CandidateFeasibilityPlanner candidateFeasibilityPlanner;

    public DispatchOrchestrator(TaskQueueService taskQueueService,
            RendezvousCoordinator rendezvous,
            OrderTaskMapper orderTaskMapper,
            CandidateFeasibilityPlanner candidateFeasibilityPlanner,
            ApplicationEventPublisher eventPublisher,
            MissionTrackAssembler missionTrackAssembler,
            TaskLogRecorder taskLogRecorder,
            DispatchPlanVerifier planVerifier,
            PlanTrackWriter planTrackWriter,
            OccupancySlotService occupancySlotService) {
        this.taskQueueService = taskQueueService;
        this.rendezvous = rendezvous;
        this.orderTaskMapper = orderTaskMapper;
        this.candidateFeasibilityPlanner = candidateFeasibilityPlanner;
        this.eventPublisher = eventPublisher;
        this.missionTrackAssembler = missionTrackAssembler;
        this.taskLogRecorder = taskLogRecorder;
        this.planVerifier = planVerifier;
        this.planTrackWriter = planTrackWriter;
        this.occupancySlotService = occupancySlotService;
    }

    /**
     * 组批 → 选机 → 会面 → 派发 → 回填会面点。
     *
     * @param orderId 待编排订单（当前状态须为「待调度」）
     * @return 派发成功的 {@code droneId}；<b>闸门熔断延时时返回 {@code null}</b>
     *         （订单已转「已受理待唤醒」，非失败——待闸门解除后重入队再进本编排）
     * @throws IllegalStateException 无可用候选组合／会面求解无解／订单状态不符
     */
    @Transactional
    public String orchestrate(String orderId) {
        // 第 1 步：组批（待调度 → 调度中；B3 闸门熔断 → 已受理待唤醒）
        taskQueueService.startDispatch(orderId);

        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在：" + orderId);
        }
        if (OrderStatus.ACCEPTED_WAIT_WAKE.getValue().equals(order.getStatus())) {
            // 熔断延后：本轮编排到此为止，不选机、不求解、不派发
            log.info("订单 {} 闸门熔断延后（已受理待唤醒），待闸门解除后重入队再编排", orderId);
            return null;
        }

        // 第 2 步：选机（C1 已接入：生成 → 逐个判可行性 → 取最近的可飞候选）
        //
        // E3（10-23）：改用带淘汰原因的返回结构。判定口径与 selectFeasible 完全一致（共用
        // evaluate 管线，不改判定逻辑／阈值／文案），差别只有一条——全数淘汰时它不抛异常、
        // 由编排层决定拒单口径。此处仍抛 IllegalStateException：本编排第 1 步已把订单推进到
        // 「调度中」，抛异常才能让事务回滚、避免「调度中」成为脏状态停留（见类注释「失败语义」）。
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                candidateFeasibilityPlanner.selectFeasibleWithReasons(order);
        logRejectedCandidates(orderId, selection);
        CandidateGenerator.CandidateCombo best = selection.chosen();
        if (best == null) {
            throw new IllegalStateException("订单 " + orderId + " 派发失败：无可用候选组合"
                    + "（候选 " + selection.candidates().size() + " 个，全部被淘汰）"
                    + rejectionDetail(selection));
        }

        // 第 3 步：会面求解（M3 动态会面，判「追得上」；无解抛异常，不得退回假时刻）
        RendezvousCoordinator.MeetPlan plan = rendezvous.solveFor(order, best.comboId(), best.droneId());

        // 第 4 步：派发（调度中 → 已派发，回填 droneId + meetTime）
        taskQueueService.dispatch(orderId, best.droneId(), plan.meetTime());

        // 第 5 步：会面点坐标回填。只定点更新会面点两列，不用开头读到的陈旧实体整行覆盖，
        // 避免把并发写入的无人机占位或状态回写冲掉。
        int rows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                .eq(OrderTaskEntity::getOrderId, orderId)
                .eq(OrderTaskEntity::getStatus, OrderStatus.DISPATCHED.getValue())
                .set(OrderTaskEntity::getMeetLon, BigDecimal.valueOf(plan.meetLon()))
                .set(OrderTaskEntity::getMeetLat, BigDecimal.valueOf(plan.meetLat())));
        if (rows != 1) {
            throw new IllegalStateException("订单 " + orderId + " 会面点回填影响行数为 " + rows + "，期望 1");
        }

        // 第 6 步：装配三段航迹 → 定案校验 → 落 plan_track → 执行机实例占位
        // → 一条任务日志摘要 → 发布规划航迹帧。全部在同一派单事务内：
        // 任一步失败随事务回滚，不留下「已派发但无航迹／无占位」的半成品。
        long nowMs = System.currentTimeMillis();
        PlanTrackFrame trackFrame = missionTrackAssembler.assemble(orderId, best.comboId(), plan,
                Long.toString(nowMs));
        DispatchPlanVerifier.Outcome outcome = planVerifier.verify(trackFrame, plan, orderId,
                comboIdForVerify(best.comboId(), orderId), best.droneId(), order.getShipMmsi(), nowMs);
        long shiftMs = outcome.shiftMs();
        PlanTrackFrame finalFrame = shiftMs > 0L ? shiftFrame(trackFrame, shiftMs) : trackFrame;
        LocalDateTime committedMeetTime = shiftMs > 0L
                ? plan.meetTime().plusNanos(shiftMs * 1_000_000L) : plan.meetTime();
        if (shiftMs > 0L) {
            int shiftedRows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                    .eq(OrderTaskEntity::getOrderId, orderId)
                    .eq(OrderTaskEntity::getStatus, OrderStatus.DISPATCHED.getValue())
                    .set(OrderTaskEntity::getMeetTime, committedMeetTime));
            if (shiftedRows != 1) {
                throw new IllegalStateException("订单 " + orderId + " 错峰后会面时刻回填影响行数为 "
                        + shiftedRows + "，期望 1");
            }
        }
        // 契约 §2.2：先落 plan_track 再推 plan_track WS 帧；落库失败抛异常并回滚派单事务。
        int pointCount = planTrackWriter.write(finalFrame);
        occupyDroneSlot(orderId, best.droneId(), committedMeetTime, nowMs);
        taskLogRecorder.record(orderId, PlanTrackFrame.OP_TYPE,
                OrderStatus.DISPATCHED.getValue(), OrderStatus.DISPATCHED.getValue(), nowMs);
        eventPublisher.publishEvent(new MissionTrackPlannedEvent(finalFrame));

        // C3：完整方案已持久化后发布投影，app 层在事务提交后转成 WebSocket 帧。
        // 会面帧投影抽成纯函数 RendezvousFrameProjector，编排器与队列侧（T1-6）共用同一口径，
        // 不重跑判定；etaTs 取 plan.meetTime()（值即 meetTMin＝会面时刻，非起飞时刻）。
        eventPublisher.publishEvent(new RendezvousPlanCommittedEvent(
                new RendezvousFrameProjector().project(new RendezvousFrameProjector.Input(
                        orderId, plan.meetLon(), plan.meetLat(), true,
                        selection.candidates().size(),
                        RendezvousFrameProjector.reasonsOf(selection),
                        committedMeetTime,
                        // 候选对比三项（10-29 第二步）：中选机巢／中选无人机／全量候选。
                        // 全量候选含中选者本身（selected=true），前端据此高亮中选、置灰落选。
                        best.nestId(),
                        best.droneId(),
                        RendezvousFrameProjector.candidateViewsOf(selection, best.comboId())))));

        log.info("派发编排完成：orderId={} droneId={} comboId={} 会面点={},{} 会面时刻={} 起飞时刻={} 错峰={}ms 计划点={} 末段预检={} 解={} 快照版本={}",
                orderId, best.droneId(), best.comboId(), plan.meetLon(), plan.meetLat(),
                committedMeetTime, outcome.takeoffMs(), shiftMs, pointCount,
                outcome.handoverPredicted(), plan.solutionId(), plan.snapshotVersion());

        return best.droneId();
    }

    /** 三个门面方法要非空 comboId；未组批时用订单号构造稳定标识。 */
    private static String comboIdForVerify(String comboId, String orderId) {
        return comboId == null || comboId.isBlank() ? "COMBO-" + orderId : comboId;
    }

    /** 错峰：整帧计划点整体后移，保持分段顺序与点数不变。 */
    private static PlanTrackFrame shiftFrame(PlanTrackFrame frame, long shiftMs) {
        List<PlanTrackFrame.Segment> shifted = frame.segments().stream()
                .map(segment -> new PlanTrackFrame.Segment(segment.kind(),
                        segment.points().stream()
                                .map(point -> new PlanTrackFrame.Point(point.lon(), point.lat(),
                                        point.altM(), point.speedMs(), point.tMs() + shiftMs))
                                .toList()))
                .toList();
        return new PlanTrackFrame(frame.orderId(), frame.routeId(), frame.comboId(),
                frame.opType(), shifted);
    }

    /**
     * 执行机实例占位进派单事务：从当前时刻到定案会面时刻占用该机，
     * 登记 orderId 使终态 {@code releaseByOrder} 能按订单释放。
     */
    private void occupyDroneSlot(String orderId, String droneId,
            LocalDateTime meetTime, long nowMs) {
        LocalDateTime t1 = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), DB_ZONE);
        LocalDateTime t2 = meetTime != null && meetTime.isAfter(t1) ? meetTime : t1.plusHours(1);
        occupancySlotService.occupyForOrder(OccupancySlotService.OBJECT_TYPE_DRONE_OCCUPY,
                droneId, t1, t2, "常规驻留", orderId);
    }

    /**
     * 落选候选的原因透出（E3）：每个不可飞候选打一条日志，机型单独成列，
     * 让「哪架机、为什么落选」在演示与排障时直接可见，不必再去翻判定端日志。
     */
    private void logRejectedCandidates(String orderId,
            CandidateFeasibilityPlanner.FeasibleSelection selection) {
        for (CandidateFeasibilityPlanner.CandidateVerdict verdict : selection.candidates()) {
            if (verdict.feasible()) {
                continue;
            }
            log.info("订单 {} 候选落选：机型={} 机巢={} 机号={} 原因={}",
                    orderId, verdict.model(), verdict.nestId(), verdict.droneId(),
                    String.join("；", verdict.reasons()));
        }
    }

    /**
     * 全数淘汰时的原因明细，挂进异常消息。
     *
     * <p>沿用原 {@code selectFeasible} 的可观测性——它的异常消息带 {@code rejectionSummary}，
     * 换用不抛异常的新方法后若不补这段，排障信息会丢失。
     */
    private String rejectionDetail(CandidateFeasibilityPlanner.FeasibleSelection selection) {
        StringBuilder detail = new StringBuilder();
        for (String reason : RendezvousFrameProjector.reasonsOf(selection)) {
            detail.append("\n  - ").append(reason);
        }
        return detail.toString();
    }

}
