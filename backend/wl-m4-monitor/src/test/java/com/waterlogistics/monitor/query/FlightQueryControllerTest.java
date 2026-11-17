package com.waterlogistics.monitor.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.rest.FlightReplayResponse;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.monitor.ingest.TelemetryService;
import com.waterlogistics.monitor.store.FlightRecordStore;
import com.waterlogistics.monitor.store.ShipPredictionReplayStore;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FlightQueryControllerTest {

    private static final TelemetryCleaner.Limits LIMITS =
            new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000);

    @Test
    void replay_shouldReturnChronologicalDroneStateFrames() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(2_000L, 70.0)));
        store.append(telemetry.acceptState(frame(1_000L, 80.0)));
        FlightQueryController controller = new FlightQueryController(new FlightQueryService(store));

        FlightReplayResponse response = controller.replay("UAV-D0-1");

        assertEquals("UAV-D0-1", response.taskId());
        assertEquals(2, response.frames().size());
        assertEquals("drone_state", response.frames().get(0).type());
        DroneStateFrame first = (DroneStateFrame) response.frames().get(0).data();
        DroneStateFrame second = (DroneStateFrame) response.frames().get(1).data();
        assertEquals(1_000L, first.ts());
        assertEquals(90.0, first.heading());
        assertNull(first.activeOrderId());
        assertEquals(2_000L, second.ts());
    }

    @Test
    void replay_shouldReturnNotFoundForUnknownTask() {
        FlightQueryController controller = new FlightQueryController(
                new FlightQueryService(new FlightRecordStore()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.replay("missing"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void statistics_shouldReportPersistedSamples() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(1_000L, 80.0)));
        FlightQueryController controller = new FlightQueryController(new FlightQueryService(store));

        assertEquals(1L, controller.statistics().get("flights"));
        assertEquals(1L, controller.statistics().get("samples"));
    }

    @Test
    void replay_shouldBindOrderAndUsePersistedStateVersion() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(1_700_000_000_000L, 80.0)));
        OrderTaskMapper orders = Mockito.mock(OrderTaskMapper.class);
        TaskLogMapper logs = Mockito.mock(TaskLogMapper.class);
        AlertMapper alerts = Mockito.mock(AlertMapper.class);
        PlanTrackMapper plans = Mockito.mock(PlanTrackMapper.class);
        ShipPredictionReplayStore predictions = Mockito.mock(ShipPredictionReplayStore.class);
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORD-1");
        order.setDroneId("UAV-D0-1");
        order.setShipMmsi("413000001");
        order.setCreateTime(local(1_699_999_999_000L));
        when(orders.selectById("ORD-1")).thenReturn(order);
        TaskLogEntity transition = new TaskLogEntity();
        transition.setOrderId("ORD-1");
        transition.setLogId(8L);
        transition.setOpType("派发");
        transition.setFromStatus("待调度");
        transition.setToStatus("已派发");
        transition.setTs(local(1_700_000_000_000L));
        when(logs.selectList(any(QueryWrapper.class))).thenReturn(List.of(transition));
        when(predictions.range("413000001", 1_700_000_000_000L, 1_700_000_000_000L))
                .thenReturn(List.of(new com.waterlogistics.contract.ws.ShipTrajFrame(
                        "413000001", 4L, 1_700_000_000_000L, 90, 600, "VALID",
                        List.of(new com.waterlogistics.contract.ws.ShipTrajPoint(
                                1_700_000_000_000L, 115.9, 29.7, 12, 8)))));
        var controller = new FlightQueryController(new FlightQueryService(store), orders, logs,
                alerts, plans, predictions);

        FlightReplayResponse response = controller.replay("ORD-1");

        assertEquals("ORD-1", response.orderId());
        assertEquals("UAV-D0-1", response.taskId());
        assertTrue(response.frames().stream().anyMatch(frame -> frame.type().equals("ship_traj")));
        var orderFrame = response.frames().stream().filter(frame -> frame.type().equals("order_state"))
                .findFirst().orElseThrow();
        assertEquals(9L, ((java.util.Map<?, ?>) orderFrame.data()).get("stateVersion"));
    }

    @Test
    void replay_shouldKeepOnlyRealOrderTransitions() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(1_700_000_000_000L, 80.0)));
        OrderTaskMapper orders = Mockito.mock(OrderTaskMapper.class);
        TaskLogMapper logs = Mockito.mock(TaskLogMapper.class);
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORD-1");
        order.setDroneId("UAV-D0-1");
        order.setCreateTime(local(1_699_999_999_000L));
        when(orders.selectById("ORD-1")).thenReturn(order);
        TaskLogEntity accepted = log(1L, "订单接入", null, "待受理");
        TaskLogEntity reviewed = log(2L, "在飞安全复核-继续", "去程中", "去程中");
        TaskLogEntity returning = log(3L, "在飞安全复核-中止返航", "去程中", "返航中");
        when(logs.selectList(any(QueryWrapper.class))).thenReturn(List.of(accepted, reviewed, returning));
        var controller = new FlightQueryController(new FlightQueryService(store), orders, logs,
                null, null, null);

        var frames = controller.replay("ORD-1").frames().stream()
                .filter(item -> item.type().equals("order_state")).toList();
        assertEquals(2, frames.size());
        assertEquals("待受理", ((java.util.Map<?, ?>) frames.get(0).data()).get("orderStatus"));
        assertEquals("返航中", ((java.util.Map<?, ?>) frames.get(1).data()).get("orderStatus"));
    }

    private static TaskLogEntity log(long id, String operation, String from, String to) {
        TaskLogEntity row = new TaskLogEntity();
        row.setOrderId("ORD-1");
        row.setLogId(id);
        row.setOpType(operation);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setTs(local(1_700_000_000_000L));
        return row;
    }

    private static LocalDateTime local(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Shanghai"));
    }


    private static UavTelemetry frame(long ts, double battery) {
        return new UavTelemetry("UAV-D0-1", 115.90, 29.70, 100, 10, battery, 90, ts);
    }
}
