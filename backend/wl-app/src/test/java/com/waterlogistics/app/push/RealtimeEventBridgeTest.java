package com.waterlogistics.app.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.contract.ws.WsFrameType;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.dispatch.prediction.PredictionUpdatedEvent;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.monitor.alert.AlertRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeEventBridgeTest {

    @Mock
    private WsPushEndpoint endpoint;

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

        verify(endpoint).publish(WsFrameType.DRONE_STATE,
                new DroneStateFrame("UAV-01", 115.91, 29.72, 83.0,
                        null, 1_700_000_000_000L));
    }

    @Test
    void alertEvent_shouldForwardAlarmFrame() {
        AlertRecorder.AlertEvent event = new AlertRecorder.AlertEvent(
                "event-1", "低电量", "警告", 12, "UAV-01", "ORD-1",
                1_700_000_000_000L, 18.0, 20.0);

        bridge.onAlertEvent(event);

        verify(endpoint).publish(eq(WsFrameType.ALARM), any(AlarmFrame.class));
    }
}
