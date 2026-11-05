package com.waterlogistics.dispatch.reschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.homecoming.HomecomingService;
import com.waterlogistics.dispatch.plan.DispatchOrchestrator;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 6b 桥段闭合点（《10-16 当日任务清单》§3.1 之「6b 接线成链」）：
 * <ul>
 *   <li>「放弃本次会面」已接 {@code dispatch.homecoming}，不再抛骨架未接线异常；</li>
 *   <li>「订单重入队」已接张睿哲的 {@link TaskQueueService#requeue}（序 4），
 *       并按《21》§8 把「落巢 → 回收确认」这两个<b>物理前置</b>做成硬校验——
 *       放过一条不该重派的单子，后果是同一票货被派给第二架机，而货物还在第一架机上。</li>
 * </ul>
 *
 * <p><b>构造器参数说明</b>：本类由吴可欣（6b）与张睿哲（重派）合并而来。
 * 2026-10-21（C0）起「选机→会面→派发」串法抽至 {@code DispatchOrchestrator}，
 * 构造器瘦身为三个依赖（重入队、返航复核、派发编排）。
 * 只测<b>前置校验</b>的用例传 {@code null} 即可——
 * 校验在调用 {@code taskQueueService} 之前就会抛异常，不会走到空指针；
 * 只有「合法路径」用例才需要真正装配 {@link RecordingTaskQueue}。
 */
class RescheduleServiceTest {

    private static final GeoPoint POSITION = new GeoPoint(120.15, 30.28);

    /** 只用于前置校验类用例：校验先于落地动作执行，故依赖可为 null */
    private final RescheduleService service = new RescheduleService(
            null, new HomecomingService(), null);

    private static HomecomingInput input(double batteryPercent) {
        return new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1", "NEST-HOME",
                POSITION, batteryPercent, 40.0, true, null, null, "LOW_BATTERY", 1_700_000_000_000L,
                Boolean.TRUE);
    }

    /** 6b 第 4 步：原机放弃本次会面并返航 → 返航复核给出三级落点结论 */
    @Test
    void abandonMeet_shouldDelegateToHomecomingReview() {
        HomecomingParams params = new HomecomingParams(20.0, 20.0, null, 0.8, 1.0, 0L, 1_000L, 2_000L);

        HomecomingDecision home = service.abandonMeetAndReturnHome(input(80.0), params, List.of());
        HomecomingDecision field = service.abandonMeetAndReturnHome(input(30.0), params, List.of());

        assertEquals(HomecomingTier.RETURN_HOME_NEST, home.tier(), "电量足：回原巢");
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, field.tier(), "电量不足且无候选他巢：迫降野外");
    }

    /** 6b 意图变更判据入口保持可用（阈值由调用方注入） */
    @Test
    void evaluateIntentChange_shouldStayUsable() {
        List<HeadingSample> window = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            window.add(new HeadingSample(100.0, 90.0, 5.0, i * 1000L));
        }

        assertFalse(service.evaluateIntentChange(window, 40.0).changed(),
                "滑窗累计 30° 小于阈值 40°，不应判变更");
        assertTrue(service.evaluateIntentChange(window, 20.0).changed(),
                "滑窗累计 30° 大于阈值 20°，应判变更");
    }

    /** MEDIUM.60：滑窗为 null 时不得静默判「未变更」，须显式拒绝（无样本≠判过且没问题） */
    @Test
    void evaluateIntentChange_shouldRejectNullWindow() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.evaluateIntentChange(null, 20.0));

        assertTrue(thrown.getMessage().contains("航向滑窗样本为空"), thrown.getMessage());
    }

    /** MEDIUM.60：空列表同样拒绝——调用方未配对样本时须先补齐，而不是拿空窗蒙混过判据 */
    @Test
    void evaluateIntentChange_shouldRejectEmptyWindow() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.evaluateIntentChange(List.of(), 20.0));

        assertTrue(thrown.getMessage().contains("航向滑窗样本为空"), thrown.getMessage());
    }

    /** 落巢＋回收确认后，重入队委托张睿哲的 TaskQueueService#requeue */
    @Test
    void requeueOrder_shouldDelegateToTaskQueueWhenLandedAndRecycled() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        queue.currentStatus = OrderStatus.DISPATCHED;
        RescheduleService wired = new RescheduleService(
                queue, new HomecomingService(), null);
        OrderRequeueCommand cmd = OrderRequeueCommand.builder()
                .orderId("ORDER-1").goodsId("GOODS-1").droneId("DRONE-1")
                .landedAtNest(true).landingPointId("NEST-A")
                .recyclingConfirmed(true).lossConfirmed(false)
                .build();

        wired.requeueOrder(cmd);

        assertEquals("ORDER-1", queue.requeuedOrderId,
                "物理前置齐备后应把落地动作交给 TaskQueueService#requeue（清原机＋回待调度＋task_log）");
    }

    /** HIGH.32：DTO 齐备但库里订单仍在飞行 → 服务端重校验拒绝，不碰 requeue */
    @Test
    void requeueOrder_shouldRejectWhenOrderStillFlying() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        queue.currentStatus = OrderStatus.OUTBOUND;
        RescheduleService wired = new RescheduleService(
                queue, new HomecomingService(), null);
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", true, "NEST-A", true, false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> wired.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("去程中"), thrown.getMessage());
        assertEquals(null, queue.requeuedOrderId, "飞行中不得落地重入队");
    }

    /** 未落巢不得重入队：货物装机后绑死在原机上，空中不可转移（《21》§8） */
    @Test
    void requeueOrder_shouldRejectWhenNotLanded() {
        OrderRequeueCommand cmd = OrderRequeueCommand.builder()
                .orderId("ORDER-1").goodsId("GOODS-1").droneId("DRONE-1")
                .landedAtNest(false).landingPointId("NEST-A")
                .recyclingConfirmed(false).lossConfirmed(false)
                .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("尚未确认落巢"),
                "须点明缺的是落巢确认：" + thrown.getMessage());
    }

    /**
     * 评审 OrderRequeueCommand:53：回收确认以落巢为前提——野外迫降点（非机巢）却已确认回收
     * 在物理上不成立，构造层即拒绝，不再依赖服务端兜底（其他消费方也不会静默接受）。
     */
    @Test
    void requeueOrder_shouldRejectFieldRecoveryClaimAtConstruction() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OrderRequeueCommand.builder()
                        .orderId("ORDER-1").goodsId("GOODS-1").droneId("DRONE-1")
                        .landedAtNest(false).landingPointId("FIELD-POINT")
                        .recyclingConfirmed(true).lossConfirmed(false)
                        .build());

        assertTrue(thrown.getMessage().contains("落巢"),
                "须点明回收确认缺落巢前提：" + thrown.getMessage());
    }

    /** 落巢但未确认回收不得重入队：回收确认才把货物转为可用 */
    @Test
    void requeueOrder_shouldRejectWhenNotRecycled() {
        OrderRequeueCommand cmd = OrderRequeueCommand.builder()
                .orderId("ORDER-1").goodsId("GOODS-1").droneId("DRONE-1")
                .landedAtNest(true).landingPointId("NEST-A")
                .recyclingConfirmed(false).lossConfirmed(false)
                .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("尚未确认货物回收"),
                "须点明缺的是回收确认：" + thrown.getMessage());
    }

    /** 灭失不得重入队：订单走「异常终止」，不存在改派（《21》§8） */
    @Test
    void requeueOrder_shouldRejectWhenLost() {
        OrderRequeueCommand cmd = OrderRequeueCommand.builder()
                .orderId("ORDER-1").goodsId("GOODS-1").droneId("DRONE-1")
                .landedAtNest(false).landingPointId("LOST-POINT")
                .recyclingConfirmed(false).lossConfirmed(true)
                .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("异常终止"),
                "须点明灭失走终止而非重派：" + thrown.getMessage());
    }

    /** CRITICAL.2：飞行中订单（货物仍在原机上）不得重派，先拒绝再谈重入队 */
    @Test
    void reschedule_shouldRejectFlyingOrder() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        queue.currentStatus = OrderStatus.OUTBOUND;
        RescheduleService wired = new RescheduleService(queue, new HomecomingService(), null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> wired.reschedule("ORDER-1"));

        assertTrue(thrown.getMessage().contains("空中不可转移"), thrown.getMessage());
        assertNull(queue.requeuedOrderId, "飞行中不得落地重入队，更不得派给第二架机");
    }

    /** 正常重派：重入队 → 编排返回新机号（Optional 有值） */
    @Test
    void reschedule_shouldReturnDroneWhenOrchestrated() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        queue.currentStatus = OrderStatus.DISPATCHED;
        RescheduleService wired = new RescheduleService(
                queue, new HomecomingService(), new StubOrchestrator("UAV-09"));

        Optional<String> droneId = wired.reschedule("ORDER-1");

        assertEquals("ORDER-1", queue.requeuedOrderId);
        assertEquals(Optional.of("UAV-09"), droneId);
    }

    /** LOW.28：闸门熔断时编排返回 null → 空 Optional，显式表达「延后」而非「派发成功」 */
    @Test
    void reschedule_shouldReturnEmptyWhenGateDeferred() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        queue.currentStatus = OrderStatus.ACCEPTED_WAIT_WAKE;
        RescheduleService wired = new RescheduleService(
                queue, new HomecomingService(), new StubOrchestrator(null));

        Optional<String> droneId = wired.reschedule("ORDER-1");

        assertTrue(droneId.isEmpty(), "闸门熔断延后不得被调用方当成派发成功");
    }

    /** 记录被调用订单的 TaskQueueService 桩（继承以覆盖 requeue/statusOf，避免引入 mock 框架） */
    private static final class RecordingTaskQueue extends TaskQueueService {

        private String requeuedOrderId;
        private OrderStatus currentStatus = OrderStatus.WAIT_DISPATCH;

        private RecordingTaskQueue() {
            super(null, null, null);
        }

        @Override
        public void requeue(String orderId) {
            this.requeuedOrderId = orderId;
        }

        @Override
        public OrderStatus statusOf(String orderId) {
            return currentStatus;
        }
    }

    /** 派发编排桩：直接给出编排结果（null 即模拟 B3 闸门熔断延后） */
    private static final class StubOrchestrator extends DispatchOrchestrator {

        private final String droneId;

        private StubOrchestrator(String droneId) {
            super(null, null, null, null, null, null, null);
            this.droneId = droneId;
        }

        @Override
        public String orchestrate(String orderId) {
            return droneId;
        }
    }
}
