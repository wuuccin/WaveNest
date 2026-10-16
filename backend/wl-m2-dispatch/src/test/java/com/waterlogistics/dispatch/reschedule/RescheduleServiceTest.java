package com.waterlogistics.dispatch.reschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.homecoming.HomecomingService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 6b 桥段闭合点（《10-16 当日任务清单》§3.1 之「6b 接线成链」）：
 * <ul>
 *   <li>「放弃本次会面」已接 {@code dispatch.homecoming}，不再抛骨架未接线异常；</li>
 *   <li>「订单重入队」已接 {@link OrderRequeuePort}，且按《21》§8 把
 *       「落巢 → 回收确认」这两个物理前置做成硬校验——放过一条不该重派的单子，
 *       后果是同一票货被派给第二架机，而货物还在第一架机上。</li>
 * </ul>
 */
class RescheduleServiceTest {

    private static final GeoPoint POSITION = new GeoPoint(120.15, 30.28);

    private final RescheduleService service = new RescheduleService();

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

    /** 16 号已接链：落巢＋回收确认后，重入队经端口落地，订单回「待调度」 */
    @Test
    void requeueOrder_shouldDelegateToPortWhenLandedAndRecycled() {
        RecordingPort port = new RecordingPort();
        RescheduleService wired = new RescheduleService(new HomecomingService(), port);
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", true, "NEST-A", true, false);

        OrderRequeuePort.RequeueResult result = wired.requeueOrder(cmd);

        assertEquals("ORDER-1", result.orderId());
        assertEquals(GoodsStatus.RECYCLED.getValue(), result.goodsStatus(),
                "落巢后货物「已回收」，这是重派的唯一入口（《21》§8）");
        assertTrue(result.timelineAppended(), "状态改变须向订单状态时间线追加节点");
        assertEquals(OrderStatus.RETURNING.getValue(), port.fromStatus, "变更前为「返航中」");
        assertEquals(OrderStatus.WAIT_DISPATCH.getValue(), port.toStatus, "变更后回「待调度」等待重派新机");
    }

    /** 未落巢不得重入队：货物装机后绑死在原机上，空中不可转移（《21》§8） */
    @Test
    void requeueOrder_shouldRejectWhenNotLanded() {
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", false, "NEST-A", true, false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("尚未确认落巢"),
                "须点明缺的是落巢确认：" + thrown.getMessage());
    }

    /** 落巢但未确认回收不得重入队：回收确认才把货物转为可用 */
    @Test
    void requeueOrder_shouldRejectWhenNotRecycled() {
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", true, "NEST-A", false, false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("尚未确认货物回收"),
                "须点明缺的是回收确认：" + thrown.getMessage());
    }

    /** 灭失不得重入队：订单走「异常终止」，不存在改派（《21》§8） */
    @Test
    void requeueOrder_shouldRejectWhenLost() {
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", false, "LOST-POINT", false, true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("异常终止"),
                "须点明灭失走终止而非重派：" + thrown.getMessage());
    }

    /** 前置条件齐备但端口未装配时必须显式报错，不得静默丢弃重入队动作 */
    @Test
    void requeueOrder_shouldReportPortNotWired() {
        OrderRequeueCommand cmd = new OrderRequeueCommand(
                "ORDER-1", "GOODS-1", "DRONE-1", true, "NEST-A", true, false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.requeueOrder(cmd));

        assertTrue(thrown.getMessage().contains("未装配"),
                "须点明是落地出口未装配：" + thrown.getMessage());
    }

    /** 记录调用参数的端口桩 */
    private static final class RecordingPort implements OrderRequeuePort {

        private String fromStatus;
        private String toStatus;

        @Override
        public RequeueResult requeue(OrderRequeueCommand cmd, String from, String to) {
            this.fromStatus = from;
            this.toStatus = to;
            return new RequeueResult(cmd.orderId(), GoodsStatus.RECYCLED.getValue(), true);
        }
    }
}