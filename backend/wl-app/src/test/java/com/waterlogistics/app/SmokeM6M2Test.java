package com.waterlogistics.app;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderAdmissionService;
import com.waterlogistics.dispatch.order.OrderSubmitRequest;
import com.waterlogistics.dispatch.order.OrderSubmitResponse;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10-15 序 1／序 6 完成判据冒烟（真实容器中间件：compose 四件套 healthy 前提下运行）：
 * ① SysParamLoader 能读到阈值（宋冰鑫 detect/ 依赖）；② 实体能跑通一次读写；
 * ③ 订单走通「待受理 → 待调度 → 调度中 → 已派发」。测试事务回滚，不污染演示库。
 */
@SpringBootTest
@Transactional
class SmokeM6M2Test {

    @Autowired
    private SysParamLoader sysParamLoader;
    @Autowired
    private OrderTaskMapper orderTaskMapper;
    @Autowired
    private TaskLogMapper taskLogMapper;
    @Autowired
    private TaskQueueService taskQueueService;
    @Autowired
    private DispatchGateService dispatchGateService;
    @Autowired
    private OrderAdmissionService admissionService;
    @Autowired
    private PredictionService predictionService;

    /** 判据：sys_param 有行、关键键可读（含 M6 新增两键）；2026-10-18 改按键断言，不锁总数 */
    @Test
    void sysParamLoaded() {
        Map<String, String> all = sysParamLoader.all();
        // 不锁总数：键数会随 10-18 灌库与后续标定变化；改为逐项断言 23 个必备键存在
        assertThat(all).containsKeys(
            // G 组检测阈值（16 键）
            "offtrackWarningThreshold", "offtrackCriticalThreshold", "offtrackDebounceCount",
            "offtrackClearCount", "offtrackDowngradeCount", "lowBatteryClear", "lowBatterySevere",
            "lowBatteryDowngradeDebounce", "heartbeatLostWindowSec", "heartbeatLossWindowSec",
            "heartbeatCheckIntervalSec", "weatherWindWarningThreshold", "weatherWindCriticalThreshold",
            "weatherWindSurgeThreshold", "weatherSurgeWindowSec", "weatherClearDebounce",
            // PR #6 会面与风况（7 键）
            "droneCruiseSpeedMs", "rendezvousHorizonSec", "rendezvousWindowMarginSec",
            "rendezvousEpsilonSec", "rendezvousMaxIter", "windSpeedMs", "windDirDeg");
        assertThat(all).containsEntry("nestCapacity", "4");
        assertThat(all).containsEntry("forcedStopSlotSec", "900");
        assertThat(all).containsEntry("sepHorizM", "50");
        // getInt/getDouble 通道可用
        assertThat(sysParamLoader.getInt("nestCapacity", 0)).isEqualTo(4);
    }

    /**
     * 判据（C0）：主链自然触发 —— 下单后由 DispatchTrigger 落库、受理，
     * 再由 DispatchOrchestrator 自动完成「组批 → 选机 → 会面 → 派发」，
     * 订单自然走到「已派发」，drone_id／meet_time／meet_lon／meet_lat 均有值，
     * 且 task_log 无「重入队」记录（首次派发不走 requeue）。
     */
    @Test
    void orderStateMachineWalk() {
        // 前置：喂一条 AIS 让该船有预测快照——候选距离过滤（机巢 5km 内）
        // 与会面求解（船轨迹快照）都以真实船位为前提；船位取在演示机巢旁
        predictionService.refresh(new AisMessage(
                "123456789", "冒烟测试船", 114.306, 30.606, 5.0, 90.0,
                System.currentTimeMillis()));

        // 下单（C0 自然触发：submit 发事件 → DispatchTrigger 落库＋受理
        //   → DispatchOrchestrator 组批 → 选机 → 会面 → 派发）
        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                "123456789", 5.5, "医疗物资",
                System.currentTimeMillis() + 3_600_000L, "URGENT"));
        assertThat(resp.accepted()).isTrue();
        String orderId = resp.orderId();

        // 自然触发后订单应已到「已派发」，四字段均有值（C0 完成判据）
        OrderTaskEntity after = orderTaskMapper.selectById(orderId);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(after.getDroneId()).isNotBlank();
        assertThat(after.getMeetTime()).isNotNull();
        assertThat(after.getMeetLon()).isNotNull();
        assertThat(after.getMeetLat()).isNotNull();

        // 状态机违规流转防护：已派发后不允许再受理
        assertThatThrownBy(() -> taskQueueService.accept(after))
            .isInstanceOf(IllegalStateException.class);

        // task_log 与流转同步留痕：订单接入(OrderAdmissionService) + 受理 + 组批调度 + 派发 = 4 条，
        // 且无「重入队」——首次派发不走 requeue（C0 判据）
        List<TaskLogEntity> logs = taskLogMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskLogEntity>()
                .eq(TaskLogEntity::getOrderId, orderId));
        assertThat(logs).hasSize(4);
        assertThat(logs).noneMatch(entry -> "重入队".equals(entry.getOpType()));
    }

    /** 判据：闸门口径（22 §3.2）——正常放行 / 熔断回显契约 record */
    @Test
    void gateContractEcho() {
        DispatchGate normal = dispatchGateService.summary();
        assertThat(normal.gateStatus()).isEqualTo(GateStatus.NORMAL);
        assertThat(normal.gateSources()).isEmpty();
        assertThat(normal.isBroken()).isFalse();

        dispatchGateService.trip(GateSource.MANUAL);
        DispatchGate broken = dispatchGateService.summary();
        assertThat(broken.gateStatus()).isEqualTo(GateStatus.BROKEN);
        assertThat(broken.gateSources()).containsExactly(GateSource.MANUAL);
        assertThat(broken.isBroken()).isTrue();

        dispatchGateService.clear(GateSource.MANUAL);
        assertThat(dispatchGateService.summary().gateStatus()).isEqualTo(GateStatus.NORMAL);
    }

    /**
     * B3 判据：组批入口查闸门。熔断时新单转「已受理待唤醒」而非拒单；
     * 闸门解除后可重入队调度。
     */
    @Test
    void gateCutoff_deferredNotRejected() {
        try {
            // 熔断
            dispatchGateService.trip(GateSource.AUTO);

            // 下单：自然触发到 startDispatch 时闸门熔断 → 已受理待唤醒（不拒单）
            OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                    "223456789", 3.0, "食品",
                    System.currentTimeMillis() + 3_600_000L, "NORMAL"));
            assertThat(resp.accepted()).isTrue();
            String orderId = resp.orderId();

            OrderTaskEntity deferred = orderTaskMapper.selectById(orderId);
            assertThat(deferred.getStatus())
                .isEqualTo(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());

            // 解除熔断后重入队 → 待调度 → 调度中
            dispatchGateService.clear(GateSource.AUTO);
            taskQueueService.requeue(orderId);
            assertThat(orderTaskMapper.selectById(orderId).getStatus())
                .isEqualTo(OrderStatus.WAIT_DISPATCH.getValue());

            taskQueueService.startDispatch(orderId);
            assertThat(orderTaskMapper.selectById(orderId).getStatus())
                .isEqualTo(OrderStatus.DISPATCHING.getValue());
        } finally {
            dispatchGateService.reset();
        }
    }
}
