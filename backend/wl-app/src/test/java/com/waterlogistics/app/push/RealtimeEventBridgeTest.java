package com.waterlogistics.app.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.contract.ws.WsFrameType;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.dispatch.prediction.PredictionUpdatedEvent;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.dispatch.taskstate.FlightPhaseAdvancer;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeEventBridgeTest {

    @Mock
    private WsPushEndpoint endpoint;

    @Mock
    private FlightPhaseAdvancer flightPhaseAdvancer;

    @Mock
    private DroneMapper droneMapper;

    private RealtimeEventBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new RealtimeEventBridge(endpoint);
    }

    @Test
    void orderState_shouldForwardWithoutQueryingOrDerivingOtherFrames() {
        OrderView view = new OrderView("ORD-1", OrderStatus.DISPATCHED,
                1_700_000_000_000L, 4L, null, null, null, null);

        bridge.onOrderStateChanged(new OrderStateChangedEvent(view));

        verify(endpoint).publish(WsFrameType.ORDER_STATE, view);
        verify(endpoint, never()).publish(eq(WsFrameType.RENDEZVOUS), any());
    }

    @Test
    void committedRendezvous_shouldForwardExactFrame() {
        RendezvousFrame frame = new RendezvousFrame("ORD-2", 115.9401, 29.7302, true);

        bridge.onRendezvousPlanCommitted(new RendezvousPlanCommittedEvent(frame));

        verify(endpoint).publish(WsFrameType.RENDEZVOUS, frame);
    }

    @Test
    void predictionSnapshot_shouldForwardContractProjection() {
        PredictionSnapshot snapshot = new PredictionSnapshot("413000001", 7L,
                1_700_000_000_000L, 90.0, 600L, "VALID",
                List.of(new ShipTrajPoint(1_700_000_000_000L, 115.9, 29.7, 12.0, 8.0)),
                null, null, null, null, null);

        var frame = snapshot.toFrame();
        bridge.onPredictionUpdated(new PredictionUpdatedEvent(frame));

        verify(endpoint).publish(WsFrameType.SHIP_TRAJ, frame);
    }

    @Test
    void uavTelemetry_shouldForwardPositionAndBatteryWithoutGuessingPhase() {
        bridge.onUavTelemetry(new UavTelemetry("UAV-01", 115.91, 29.72,
                100.0, 12.0, 83.0, 45.0, 1_700_000_000_000L));

        // T1-2：航向取自遥测；无推进器时不查在飞订单，activeOrderId 留空。
        verify(endpoint).publish(WsFrameType.DRONE_STATE,
                new DroneStateFrame("UAV-01", 115.91, 29.72, 83.0,
                        null, 1_700_000_000_000L, 45.0, null));
    }

    @Test
    void uavTelemetry_shouldFillActiveOrderIdFromFlightPhaseCache() {
        RealtimeEventBridge bridge = new RealtimeEventBridge(endpoint, flightPhaseAdvancer);
        when(flightPhaseAdvancer.activeOrderIdOf("UAV-01")).thenReturn("ORD-1");

        bridge.onUavTelemetry(new UavTelemetry("UAV-01", 115.91, 29.72,
                100.0, 12.0, 83.0, 45.0, 1_700_000_000_000L));

        verify(endpoint).publish(WsFrameType.DRONE_STATE,
                new DroneStateFrame("UAV-01", 115.91, 29.72, 83.0,
                        null, 1_700_000_000_000L, 45.0, "ORD-1"));
    }

    @Test
    void uavTelemetry_shouldForwardRecordedDroneReturnStatus() {
        RealtimeEventBridge bridge = new RealtimeEventBridge(endpoint, flightPhaseAdvancer, droneMapper);
        DroneEntity drone = new DroneEntity();
        drone.setStatus("返航中");
        when(droneMapper.selectById("UAV-01")).thenReturn(drone);
        when(flightPhaseAdvancer.activeOrderIdOf("UAV-01")).thenReturn("ORD-1");

        bridge.onUavTelemetry(new UavTelemetry("UAV-01", 115.91, 29.72,
                100.0, 12.0, 83.0, 45.0, 1_700_000_000_000L));

        verify(endpoint).publish(WsFrameType.DRONE_STATE,
                new DroneStateFrame("UAV-01", 115.91, 29.72, 83.0,
                        "返航中", 1_700_000_000_000L, 45.0, "ORD-1"));
    }

    @Test
    void alertEvent_shouldForwardAlarmFrame() {
        AlertEventMessage event = new AlertEventMessage(
                "event-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0);

        bridge.onAlertEvent(event);

        verify(endpoint).publish(eq(WsFrameType.ALARM), any(AlarmFrame.class));
    }

    /** 11-19 天气帧：M5 独立队列消费网格点风况后原样转发，不判气象、不缓存、不改字段。 */
    @Test
    void weatherData_shouldForwardObservationAsIs() {
        WeatherData observation = new WeatherData(115.862, 28.666,
                16.0, 135.0, 19.2, 1_700_000_000_000L, 1_700_000_300_000L, 1_700_000_000_000L);

        bridge.onWeatherData(observation);

        verify(endpoint).publish(WsFrameType.WEATHER, observation);
        verify(endpoint, never()).publish(eq(WsFrameType.ALARM), any());
    }

    /** T1-2：告警帧的位置、订单号、事件号由发送侧写入，出口层原样透传，不补默认坐标。 */
    @Test
    void alertEvent_shouldCarryPositionOrderIdAndEventIdFromMessage() {
        AlertEventMessage event = new AlertEventMessage(
                "event-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0, 115.91, 29.72);

        bridge.onAlertEvent(event);

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        AlarmFrame frame = captor.getValue();
        assertEquals(115.91, frame.lon().doubleValue(), 1e-9);
        assertEquals(29.72, frame.lat().doubleValue(), 1e-9);
        assertEquals("ORD-1", frame.orderId());
        assertEquals("event-1", frame.eventId());
    }

    /** T2-1：消息自带订单号 → 原样透传，且完全不反查（自带的是检测层当轮任务态，最准）。 */
    @Test
    void alertEvent_withOrderId_shouldNotLookupActiveOrder() {
        RealtimeEventBridge bridge = new RealtimeEventBridge(endpoint, flightPhaseAdvancer);

        bridge.onAlertEvent(new AlertEventMessage(
                "event-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertEquals("ORD-1", captor.getValue().orderId());
        verify(flightPhaseAdvancer, never()).activeOrderIdOf(any());
    }

    /** T2-1：告警未带订单号 → 帧里按机号反查当前在飞订单补上，与 M2 侧决策用的是同一个单号。 */
    @Test
    void alertEvent_withoutOrderId_shouldFillOrderIdFromFlightPhaseCache() {
        RealtimeEventBridge bridge = new RealtimeEventBridge(endpoint, flightPhaseAdvancer);
        when(flightPhaseAdvancer.activeOrderIdOf("UAV-01")).thenReturn("ORD-9");

        bridge.onAlertEvent(new AlertEventMessage(
                "event-1", "低电量", "警告", 12, "UAV-01", null,
                1_700_000_000_000L, 18.0, 20.0));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertEquals("ORD-9", captor.getValue().orderId());
    }

    /** T2-1：告警未带订单号且反查未命中 → 订单号留空，不臆造。 */
    @Test
    void alertEvent_withoutOrderId_andLookupMiss_shouldLeaveOrderIdNull() {
        RealtimeEventBridge bridge = new RealtimeEventBridge(endpoint, flightPhaseAdvancer);
        when(flightPhaseAdvancer.activeOrderIdOf("UAV-01")).thenReturn(null);

        bridge.onAlertEvent(new AlertEventMessage(
                "event-1", "低电量", "警告", 12, "UAV-01", null,
                1_700_000_000_000L, 18.0, 20.0));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertNull(captor.getValue().orderId());
    }

    /** 订单级告警只写订单号与展示名，宽限秒数来自事件载荷，不出现「无人机 null」。 */
    @Test
    void alertEvent_orderLevel_shouldNotMentionDrone() {
        bridge.onAlertEvent(new AlertEventMessage(
                "event-2", AlertType.WAIT_CARGO_TIMEOUT.getValue(), "警告", 0, null, "ORD-1",
                1_700_000_000_000L, null, 300.0));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertEquals("订单 ORD-1：等待货物到巢（宽限期 300 秒）", captor.getValue().message());
        assertFalse(captor.getValue().message().contains("无人机"));
    }

    /** 订单待处置同为订单级：只写订单号，不因机号为空退化成无人机级。 */
    @Test
    void alertEvent_orderPending_shouldStayOrderLevelWithoutDrone() {
        bridge.onAlertEvent(new AlertEventMessage(
                "event-3", AlertType.ORDER_PENDING.getValue(), "警告", 0, null, "ORD-2",
                1_700_000_000_000L, null, null));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertEquals("订单 ORD-2：订单待处置", captor.getValue().message());
    }

    /** 无人机级告警保留真实机号、当前值与阈值。 */
    @Test
    void alertEvent_droneLevel_shouldKeepRealDroneId() {
        bridge.onAlertEvent(new AlertEventMessage(
                "event-4", AlertType.DEVIATION.getValue(), "警告", 8, "UAV-07", "ORD-1",
                1_700_000_000_000L, 42.0, 30.0));

        ArgumentCaptor<AlarmFrame> captor = ArgumentCaptor.forClass(AlarmFrame.class);
        verify(endpoint).publish(eq(WsFrameType.ALARM), captor.capture());
        assertEquals("无人机 UAV-07：偏航，当前值 42.0，阈值 30.0", captor.getValue().message());
        assertTrue(captor.getValue().message().contains("UAV-07"));
    }
}
