package com.waterlogistics.dispatch.plan;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.candidate.CandidateSelector;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
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
 *   <li>{@link CandidateFeasibilityPlanner#selectFeasible} 选机（<b>C1 已接入 10-21</b>：
 *       生成 → 逐个判可行性 → 取最近的可飞候选；原为「只选不判」的
 *       {@code CandidateSelector#selectBest}）；</li>
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

    private final TaskQueueService taskQueueService;
    private final CandidateSelector candidateSelector;
    private final RendezvousCoordinator rendezvous;
    private final OrderTaskMapper orderTaskMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * C1：选机段的可行性择优（卢肄文）。生成 → 逐个判可行性 → 取最近的可飞候选。
     *
     * <p>取代原先单点调用 {@code candidateSelector.selectBest}（只选不判）——
     * 见本类第 2 步与 {@link CandidateFeasibilityPlanner} 的类注释。
     */
    private final CandidateFeasibilityPlanner candidateFeasibilityPlanner;

    public DispatchOrchestrator(TaskQueueService taskQueueService,
            CandidateSelector candidateSelector,
            RendezvousCoordinator rendezvous,
            OrderTaskMapper orderTaskMapper,
            CandidateFeasibilityPlanner candidateFeasibilityPlanner,
            ApplicationEventPublisher eventPublisher) {
        this.taskQueueService = taskQueueService;
        this.candidateSelector = candidateSelector;
        this.rendezvous = rendezvous;
        this.orderTaskMapper = orderTaskMapper;
        this.candidateFeasibilityPlanner = candidateFeasibilityPlanner;
        this.eventPublisher = eventPublisher;
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
        if (!OrderStatus.DISPATCHING.getValue().equals(order.getStatus())) {
            // 熔断延后：本轮编排到此为止，不选机、不求解、不派发
            log.info("订单 {} 闸门熔断延后（已受理待唤醒），待闸门解除后重入队再编排", orderId);
            return null;
        }

        // 第 2 步：选机（C1 已接入：生成 → 逐个判可行性 → 取最近的可飞候选）
        CandidateGenerator.CandidateCombo best = candidateFeasibilityPlanner.selectFeasible(order);
        if (best == null) {
            throw new IllegalStateException("订单 " + orderId + " 派发失败：无可用候选组合");
        }

        // 第 3 步：会面求解（M3 动态会面，判「追得上」；无解抛异常，不得退回假时刻）
        RendezvousCoordinator.MeetPlan plan = rendezvous.solveFor(order, best.comboId(), best.droneId());

        // 第 4 步：派发（调度中 → 已派发，回填 droneId + meetTime）
        taskQueueService.dispatch(orderId, best.droneId(), plan.takeoffTime());

        // 第 5 步：会面点坐标回填
        order.setMeetLon(BigDecimal.valueOf(plan.meetLon()));
        order.setMeetLat(BigDecimal.valueOf(plan.meetLat()));
        orderTaskMapper.updateById(order);
        // C3：完整方案已持久化后发布投影，app 层在事务提交后转成 WebSocket 帧。
        eventPublisher.publishEvent(new RendezvousPlanCommittedEvent(
                new RendezvousFrame(orderId, plan.meetLon(), plan.meetLat(), true)));

        log.info("派发编排完成：orderId={} droneId={} comboId={} 会面点={},{} 起飞={} 解={} 快照版本={}",
                orderId, best.droneId(), best.comboId(), plan.meetLon(), plan.meetLat(),
                plan.takeoffTime(), plan.solutionId(), plan.snapshotVersion());

        return best.droneId();
    }
}
