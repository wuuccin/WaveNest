package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.AlertDrivenDispatcher;
import com.waterlogistics.dispatch.alert.HomecomingDecisionEvent;
import com.waterlogistics.dispatch.alert.HomecomingInputAssembler;
import com.waterlogistics.dispatch.homecoming.HomecomingService;
import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.dispatch.taskstate.FlightPhaseAdvancer;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T2-1 告警订单号反查：告警未带订单号时按机号反查当前在飞订单，
 * 让偏航重调度与低电量／失联的返航复核<b>真正触发</b>，而不是空着直接 return。
 *
 * <p>不启 Spring、不依赖中间件：全部依赖用 Mockito 替身，只验「解析出的订单号有没有被真正用上」。
 * 反查源 {@code FlightPhaseAdvancer} 在此被替身化——它自身的口径（跳过已飞完、按 meet_time 取最新）
 * 由其实现保证，本类不重复验。
 */
@ExtendWith(MockitoExtension.class)
class AlertDrivenDispatcherOrderLookupTest {

    private static final String DRONE = "UAV-01";
    private static final String ORDER = "ORD-1";

    @Mock
    private RescheduleService rescheduleService;
    @Mock
    private HomecomingService homecomingService;
    @Mock
    private HomecomingInputAssembler assembler;
    @Mock
    private OrderTaskMapper orderTaskMapper;
    @Mock
    private DroneMapper droneMapper;
    @Mock
    private TaskLogMapper taskLogMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private FlightPhaseAdvancer flightPhaseAdvancer;

    private AlertDrivenDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new AlertDrivenDispatcher(rescheduleService, homecomingService, assembler,
                orderTaskMapper, droneMapper, taskLogMapper, eventPublisher, flightPhaseAdvancer);
    }

    /** ① 偏航告警未带单号、反查命中 → 真正触发重调度，且用的是反查出的单号。 */
    @Test
    void deviation_withoutOrderId_shouldRescheduleWithLookedUpOrderId() {
        when(flightPhaseAdvancer.activeOrderIdOf(DRONE)).thenReturn(ORDER);
        when(rescheduleService.reschedule(ORDER)).thenReturn("UAV-02");

        dispatcher.route(alert(AlertType.DEVIATION, null));

        verify(rescheduleService).reschedule(ORDER);
    }

    /** ② 低电量告警未带单号、反查命中 → 返航复核走完（落审计 ＋ 广播），广播里的单号是反查值。 */
    @Test
    void lowBattery_withoutOrderId_shouldRunHomecomingReviewWithLookedUpOrderId() {
        when(flightPhaseAdvancer.activeOrderIdOf(DRONE)).thenReturn(ORDER);
        when(orderTaskMapper.selectById(ORDER)).thenReturn(order());
        when(assembler.build(any(), any(), any())).thenReturn(homecomingInput());
        when(assembler.params())
                .thenReturn(HomecomingParams.suggested(0.8, 1.2, 1_700_000_000_000L, 60_000L));
        when(homecomingService.reviewAndDecide(any(), any(), anyList())).thenReturn(decision());

        dispatcher.route(alert(AlertType.LOW_BATTERY, null));

        verify(orderTaskMapper).selectById(ORDER);
        verify(homecomingService).reviewAndDecide(any(), any(), anyList());
        verify(taskLogMapper).insert(any(TaskLogEntity.class));
        ArgumentCaptor<HomecomingDecisionEvent> captor =
                ArgumentCaptor.forClass(HomecomingDecisionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER);
    }

    /** ③ 未带单号且反查未命中 → 不触发任何下游动作（不臆造单号、不误伤订单）。 */
    @Test
    void withoutOrderId_andLookupMiss_shouldNotTriggerAnyDownstream() {
        when(flightPhaseAdvancer.activeOrderIdOf(DRONE)).thenReturn(null);

        dispatcher.route(alert(AlertType.DEVIATION, null));

        verify(rescheduleService, never()).reschedule(any());
        verify(orderTaskMapper, never()).selectById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** ③' 低电量未带单号且反查未命中 → 不落审计、不广播（返航复核整条链路都不启动）。 */
    @Test
    void lowBattery_withoutOrderId_andLookupMiss_shouldNotAuditNorBroadcast() {
        when(flightPhaseAdvancer.activeOrderIdOf(DRONE)).thenReturn(null);

        dispatcher.route(alert(AlertType.LOW_BATTERY, null));

        verify(taskLogMapper, never()).insert(any(TaskLogEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** ④ 消息自带单号 → 用自带值，且完全不反查（自带的是检测层当轮任务态，最准）。 */
    @Test
    void withOrderId_shouldUseCarriedOrderIdWithoutLookup() {
        dispatcher.route(alert(AlertType.DEVIATION, ORDER));

        verify(rescheduleService).reschedule(ORDER);
        verify(flightPhaseAdvancer, never()).activeOrderIdOf(any());
    }

    private static AlertEventMessage alert(AlertType type, String orderId) {
        return new AlertEventMessage("EV-1", type.getValue(), "警告", 30,
                DRONE, orderId, 1_700_000_000_000L, 18.0, 20.0);
    }

    private static OrderTaskEntity order() {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(ORDER);
        order.setDroneId(DRONE);
        order.setStatus(OrderStatus.DISPATCHED.getValue());
        order.setMeetLon(BigDecimal.valueOf(115.94));
        order.setMeetLat(BigDecimal.valueOf(29.73));
        return order;
    }

    private static HomecomingInput homecomingInput() {
        return new HomecomingInput("EV-1", "ALERT", ORDER, ORDER, DRONE, "NEST-1",
                new GeoPoint(115.94, 29.73), 18.0, 10.0, false, null, null,
                "低电量", 1_700_000_000_000L, Boolean.TRUE);
    }

    private static HomecomingDecision decision() {
        return new HomecomingDecision(HomecomingTier.RETURN_HOME_NEST, "NEST-1",
                new GeoPoint(115.94, 29.73), false, List.of(), List.of(), "电量满足回原巢");
    }
}
