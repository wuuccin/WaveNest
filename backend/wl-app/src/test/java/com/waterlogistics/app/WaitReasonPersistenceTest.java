package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderAdmissionService;
import com.waterlogistics.dispatch.order.OrderSubmitRequest;
import com.waterlogistics.dispatch.order.OrderSubmitResponse;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 11-05 序号 1「等待原因落库」判据（真实 MySQL）：
 * 组批入口闸门熔断把订单推进「已受理待唤醒」时，{@code order_task.wait_reason}
 * 必须与状态同时落库，并由所有后续流转清空——而不是只挂在 {@code OrderView} 上。
 *
 * <p>只验 {@code TaskQueueService.transition} 这条入口（本序号交付点）；
 * 接单阶段直接落「已受理待唤醒」的 DispatchTrigger 路径归卢肄文的货位／等待恢复任务。
 * 测试事务回滚，不污染演示库。
 */
@SpringBootTest(properties = {
        "wavenest.m2.flight-advance-enabled=false",
        // 仿真 AIS 流会覆盖手喂船位，关掉后本用例独占喂数
        "wavenest.sim.enabled=false"})
@Transactional
class WaitReasonPersistenceTest {

    private static final String SHIP = "123456789";

    @Autowired
    private OrderAdmissionService admissionService;
    @Autowired
    private PredictionService predictionService;
    @Autowired
    private TaskQueueService taskQueueService;
    @Autowired
    private DispatchGateService gateService;
    @Autowired
    private OrderTaskMapper orderTaskMapper;

    @Test
    void gateDeferralShouldPersistWaitReasonAndClearOnLeavingWaitState() {
        // 前置：南昌赣江演示航段船位，候选与会面求解都依赖真实预测快照
        predictionService.refresh(new AisMessage(
                SHIP, "等待原因测试船", 115.8590, 28.6630, 5.0, 90.0,
                System.currentTimeMillis()));

        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                SHIP, 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT"));
        assertThat(resp.accepted()).isTrue();
        String orderId = resp.orderId();
        assertThat(orderTaskMapper.selectById(orderId).getStatus())
                .isEqualTo(OrderStatus.DISPATCHED.getValue());

        try {
            // 回到待调度，制造「组批入口闸门熔断」场景（B3）
            taskQueueService.requeue(orderId);
            gateService.trip(GateSource.AUTO);
            taskQueueService.startDispatch(orderId);

            OrderTaskEntity waiting = orderTaskMapper.selectById(orderId);
            assertThat(waiting.getStatus()).isEqualTo(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());
            assertThat(waiting.getWaitReason()).isEqualTo(WaitReason.GATE_OR_DATA.getCode());

            // 解除熔断并重入队 → 离开等待态，原因必须清空，避免前端回显过期原因
            gateService.clear(GateSource.AUTO);
            taskQueueService.requeue(orderId);
            OrderTaskEntity requeued = orderTaskMapper.selectById(orderId);
            assertThat(requeued.getStatus()).isEqualTo(OrderStatus.WAIT_DISPATCH.getValue());
            assertThat(requeued.getWaitReason()).isNull();
        } finally {
            gateService.clear(GateSource.AUTO);
        }
    }
}
