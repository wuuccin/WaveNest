package com.waterlogistics.app;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
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

import java.time.LocalDateTime;
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

    /** 判据：实体读写 + 状态机主链三步流转 + task_log 同步留痕 */
    @Test
    void orderStateMachineWalk() {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("SMOKE-001");
        order.setShipMmsi("123456789");
        order.setCreateTime(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING_ACCEPT.getValue());
        orderTaskMapper.insert(order);

        taskQueueService.accept(order);
        assertThat(orderTaskMapper.selectById("SMOKE-001").getStatus())
            .isEqualTo(OrderStatus.WAIT_DISPATCH.getValue());

        taskQueueService.startDispatch("SMOKE-001");
        assertThat(orderTaskMapper.selectById("SMOKE-001").getStatus())
            .isEqualTo(OrderStatus.DISPATCHING.getValue());

        taskQueueService.dispatch("SMOKE-001", "DRONE-SMOKE", LocalDateTime.now().plusMinutes(30));
        OrderTaskEntity after = orderTaskMapper.selectById("SMOKE-001");
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(after.getDroneId()).isEqualTo("DRONE-SMOKE");

        // 状态机违规流转防护：已派发后不允许再受理
        assertThatThrownBy(() -> taskQueueService.accept(after))
            .isInstanceOf(IllegalStateException.class);

        // task_log 与流转同步留痕（受理/组批调度/派发 = 3 条，中文状态词）
        Long logs = taskLogMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskLogEntity>()
                .eq(TaskLogEntity::getOrderId, "SMOKE-001"));
        assertThat(logs).isEqualTo(3L);
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
}
