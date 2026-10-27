package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.AlertDrivenDispatcher;
import com.waterlogistics.dispatch.alert.HomecomingDecisionEvent;
import com.waterlogistics.dispatch.alert.WeatherClearWaker;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderAdmissionService;
import com.waterlogistics.dispatch.order.OrderSubmitRequest;
import com.waterlogistics.dispatch.order.OrderSubmitResponse;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * F1 告警驱动重调度完成判据（真实容器中间件：compose 四件套 healthy 前提下运行）：
 * 三条分派路径各走对分支；坏消息吞掉不中断；气象解除边沿唤醒积压。
 * 测试事务回滚，不污染演示库。
 *
 * <p>MQ 侧不经真实 Rabbit 转发，直接调 {@code route}（消息体契约已由 F0a 的
 * Jackson 全自动映射保证）；监听器的队列拓扑在装配层冒烟时验证。
 *
 * <p><b>隔离口径</b>：{@code DecisionCapture} 与消费器的 eventId 去重集是<b>上下文级单例</b>，
 * 事务只回滚 DB 不回滚内存态——广播断言一律按本用例的 eventId 过滤，不锁全量列表。
 */
@SpringBootTest(properties =
        // T1-5 起机巢在南昌航段、仿真器仍在旧域（杨添爻 T1-4 切换前），
        // 仿真 AIS 流会覆盖本测试手喂的船位快照导致候选全灭——关掉仿真流，测试独占喂数
        "wavenest.sim.enabled=false")
@Transactional
class AlertDrivenRescheduleTest {

    private static final String SHIP = "123456789";
    private static final String DRONE = "UAV-01";
    private static final long NOW = System.currentTimeMillis();

    @Autowired
    private AlertDrivenDispatcher dispatcher;
    @Autowired
    private WeatherClearWaker waker;
    @Autowired
    private OrderAdmissionService admissionService;
    @Autowired
    private PredictionService predictionService;
    @Autowired
    private DispatchGateService gateService;
    @Autowired
    private OrderTaskMapper orderTaskMapper;
    @Autowired
    private TaskLogMapper taskLogMapper;
    @Autowired
    private DecisionCapture capture;

    /** 收集返航复核广播（F1 的「广播」出口，进程内事件） */
    @TestConfiguration
    static class CaptureConfig {
        @Bean
        DecisionCapture decisionCapture() {
            return new DecisionCapture();
        }
    }

    static class DecisionCapture {
        final List<HomecomingDecisionEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(HomecomingDecisionEvent event) {
            events.add(event);
        }
    }

    /** 前置：喂船位快照（候选距离过滤与会面求解前提），下单走完整主链到「已派发」 */
    private String dispatchedOrder() {
        // 船位取南昌赣江演示航段南昌大桥旁（T1-5 起机巢在航段两端，两巢均在 5km 内）
        predictionService.refresh(new AisMessage(
                SHIP, "F1测试船", 115.8590, 28.6630, 5.0, 90.0, System.currentTimeMillis()));
        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                SHIP, 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT"));
        assertThat(resp.accepted()).isTrue();
        OrderTaskEntity order = orderTaskMapper.selectById(resp.orderId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(order.getMeetLon()).isNotNull();
        return resp.orderId();
    }

    private List<HomecomingDecisionEvent> eventsOf(String... eventIds) {
        return capture.events.stream()
                .filter(event -> List.of(eventIds).contains(event.eventId()))
                .collect(Collectors.toList());
    }

    private List<TaskLogEntity> logsOf(String orderId) {
        return taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogEntity>()
                .eq(TaskLogEntity::getOrderId, orderId));
    }

    @Test
    void deviationAlert_shouldRequeueAndRedispatch() {
        String orderId = dispatchedOrder();

        dispatcher.route(new AlertEventMessage(
                "EV-D1", "偏航", "警告", 5, DRONE, orderId, NOW, 55.0, 30.0));

        // 重调度＝重入队＋派发编排：订单再次走到「已派发」（演示口径，清单分派表第 1 行）
        OrderTaskEntity after = orderTaskMapper.selectById(orderId);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(after.getDroneId()).isNotBlank();

        List<TaskLogEntity> logs = logsOf(orderId);
        assertThat(logs).extracting(TaskLogEntity::getOpType)
                .contains("重入队");
        assertThat(logs).extracting(TaskLogEntity::getOpType)
                .filteredOn(op -> "派发".equals(op))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void lowBatteryAlert_shouldReviewAndBroadcast() {
        String orderId = dispatchedOrder();

        // 电量 18% < 基线 20%＋储备 20%＝40%，候选为空 → 第三级迫降野外
        dispatcher.route(new AlertEventMessage(
                "EV-L1", "低电量", "警告", 10, DRONE, orderId, NOW, 18.0, 20.0));

        List<HomecomingDecisionEvent> mine = eventsOf("EV-L1");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).orderId()).isEqualTo(orderId);
        assertThat(mine.get(0).tier()).isEqualTo(HomecomingTier.EMERGENCY_LAND_FIELD);
        assertThat(mine.get(0).reason()).isNotBlank();

        // 决策落审计：task_log 有「返航复核-低电量」，审计不流转状态
        assertThat(logsOf(orderId)).extracting(TaskLogEntity::getOpType)
                .contains("返航复核-低电量");
    }

    @Test
    void lowBatteryHigh_shouldReturnHomeNest() {
        String orderId = dispatchedOrder();

        // 电量 60% ≥ 40% → 第一级回原巢（A1 判据正向）
        dispatcher.route(new AlertEventMessage(
                "EV-L2", "低电量", "提示", 3, DRONE, orderId, NOW, 60.0, 20.0));

        List<HomecomingDecisionEvent> mine = eventsOf("EV-L2");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).tier()).isEqualTo(HomecomingTier.RETURN_HOME_NEST);
    }

    @Test
    void lostLinkAlert_shouldFallbackToDroneBatteryAndReview() {
        String orderId = dispatchedOrder();

        // 失联消息无当前值 → 退 drone 表最近电量；commCovered=FALSE 仅作风险标记不作否决
        dispatcher.route(new AlertEventMessage(
                "EV-K1", "失联", "严重", 35, DRONE, orderId, NOW, null, null));

        List<HomecomingDecisionEvent> mine = eventsOf("EV-K1");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).tier()).isEqualTo(HomecomingTier.RETURN_HOME_NEST);
        assertThat(mine.get(0).needSlotRequest()).isFalse();

        assertThat(logsOf(orderId)).extracting(TaskLogEntity::getOpType)
                .contains("返航复核-失联");
    }

    @Test
    void sameEventId_shouldReviewOnlyOnce() {
        String orderId = dispatchedOrder();

        // 同一 eventId 的「级别变化」复用 id 再发——复核只做一次（G-01 贯穿始终）
        dispatcher.route(new AlertEventMessage(
                "EV-L3", "低电量", "警告", 10, DRONE, orderId, NOW, 18.0, 20.0));
        dispatcher.route(new AlertEventMessage(
                "EV-L3", "低电量", "严重", 40, DRONE, orderId, NOW, 10.0, 10.0));

        assertThat(eventsOf("EV-L3")).hasSize(1);
    }

    @Test
    void badMessage_shouldBeSwallowedAndQueueAlive() {
        dispatchedOrder();

        // 未知类型：onAlertEvent 吞掉不向外抛（边界 3）
        assertThatCode(() -> dispatcher.onAlertEvent(new AlertEventMessage(
                "EV-X1", "不存在的类型", "警告", 1, DRONE, "ORDER-404", NOW, 1.0, 1.0)))
                .doesNotThrowAnyException();
        // 缺订单号的偏航：跳过不抛
        assertThatCode(() -> dispatcher.route(new AlertEventMessage(
                "EV-X2", "偏航", "严重", 1, DRONE, null, NOW, 99.0, 30.0)))
                .doesNotThrowAnyException();

        // 队列仍然活着：随后的合法消息正常处理
        String orderId = dispatchedOrder();
        dispatcher.route(new AlertEventMessage(
                "EV-D2", "偏航", "警告", 5, DRONE, orderId, NOW, 55.0, 30.0));
        assertThat(orderTaskMapper.selectById(orderId).getStatus())
                .isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(eventsOf("EV-X1", "EV-X2", "EV-D2")).isEmpty();
    }

    @Test
    void weatherClear_shouldWakeBacklog() {
        predictionService.refresh(new AisMessage(
                SHIP, "F1测试船", 115.8590, 28.6630, 5.0, 90.0, System.currentTimeMillis()));

        // 熔断中下单 → B3 延后语义：已受理待唤醒（不是拒单）
        gateService.trip(GateSource.AUTO);
        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                SHIP, 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT"));
        assertThat(resp.accepted()).isTrue();
        assertThat(orderTaskMapper.selectById(resp.orderId()).getStatus())
                .isEqualTo(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());

        // 闸门仍闭：轮询不唤醒（边沿未命中）
        waker.tick();
        assertThat(orderTaskMapper.selectById(resp.orderId()).getStatus())
                .isEqualTo(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());

        // 气象解除（B2 clear）→ 边沿命中 → 逐单重入队＋续编排
        gateService.clear(GateSource.AUTO);
        waker.tick();

        OrderTaskEntity after = orderTaskMapper.selectById(resp.orderId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(after.getDroneId()).isNotBlank();
        assertThat(logsOf(resp.orderId())).extracting(TaskLogEntity::getOpType)
                .contains("闸门熔断-延后", "重入队");

        // 闸门持续打开：再拍不重复唤醒（边沿触发，订单已不在积压）
        waker.tick();
        assertThat(orderTaskMapper.selectById(resp.orderId()).getStatus())
                .isEqualTo(OrderStatus.DISPATCHED.getValue());
    }
}
