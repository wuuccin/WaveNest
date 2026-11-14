package com.waterlogistics.monitor.taskstate;

import static org.junit.jupiter.api.Assertions.*;

import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.TaskStatus;
import com.waterlogistics.monitor.track.FlightContextProvider;
import com.waterlogistics.monitor.track.FlightTracker;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryTaskStateServiceTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void cleanedTelemetryUpdatesProgressAndEta() {
        Fixture fixture = new Fixture(TaskStatus.DISPATCHED);
        TelemetryState state = state();
        var context = context();
        var measurement = new FlightTracker().measure(state, context.route(), context.landing(), false);
        ProgressEta eta = fixture.service.onTelemetry(state, context, measurement);
        assertTrue(eta.computable());
        assertTrue(eta.etaMs() > NOW);
        assertTrue(measurement.progress().fraction() > 0);
        assertEquals(measurement.progress(), fixture.service.latestProgress("ORDER-1").progress());
        assertEquals(eta, fixture.service.latestProgress("ORDER-1").eta());
        assertEquals(TaskStatus.OUTBOUND.getValue(), fixture.order.getStatus());
        assertEquals(1, fixture.logs.size());
        assertEquals(1, fixture.etaWrites);
    }

    @Test
    void missingMeasurementDoesNotInventEta() {
        Fixture fixture = new Fixture(TaskStatus.DISPATCHED);
        assertNull(fixture.service.onTelemetry(state(), context(), null));
        assertEquals(0, fixture.orderWrites);
    }

    @Test
    void successfulHandoverChangesTaskAndGoods() {
        Fixture fixture = new Fixture(TaskStatus.HANDOVER);
        var confirmation = new B_HandoverConfirmation("TASK-1", "UAV-1", 115.86, 28.69,
                115.86, 28.69, NOW, NOW + 1000, 1, 1, 0.1, HandoverResult.SUCCESS);
        assertTrue(fixture.service.onHandover("ORDER-1", confirmation).confirmed());
        assertEquals(TaskStatus.RETURNING.getValue(), fixture.order.getStatus());
        assertEquals(GoodsStatus.DELIVERED.getValue(), fixture.goods.getStatus());
        assertEquals(1, fixture.logs.size());
    }

    @Test
    void failedHandoverDoesNotChangeTaskOrGoods() {
        Fixture fixture = new Fixture(TaskStatus.HANDOVER);
        var confirmation = new B_HandoverConfirmation("TASK-1", "UAV-1", 115.86, 28.69,
                115.86, 28.69, NOW, null, null, null, null, HandoverResult.FAILED);
        assertFalse(fixture.service.onHandover("ORDER-1", confirmation).confirmed());
        assertEquals(TaskStatus.HANDOVER.getValue(), fixture.order.getStatus());
        assertEquals(GoodsStatus.LOADED.getValue(), fixture.goods.getStatus());
        assertEquals(0, fixture.orderWrites);
    }

    private static TelemetryState state() {
        return new TelemetryState(new UavTelemetry("UAV-1", 115.86, 28.665, 100, 10, 80, 0, NOW),
                "observed", false, "good", List.of(), null, null, "WGS-84", Map.of());
    }

    private static FlightContextProvider.FlightContext context() {
        Geo.Point start = new Geo.Point(115.86, 28.64);
        Geo.Point end = new Geo.Point(115.86, 28.69);
        return new FlightContextProvider.FlightContext("ORDER-1", "去程",
                new RouteMeasurement.Route("route-1", 1, List.of(start, end)),
                new RouteMeasurement.Landing("会面点", "ORDER-1", end, 1, 1));
    }

    private static final class Fixture {
        final OrderTaskEntity order = new OrderTaskEntity();
        final GoodsEntity goods = new GoodsEntity();
        final List<TaskLogEntity> logs = new ArrayList<>();
        final TelemetryTaskStateService service;
        int orderWrites;
        int etaWrites;

        Fixture(TaskStatus status) {
            order.setOrderId("ORDER-1");
            order.setDroneId("UAV-1");
            order.setStatus(status.getValue());
            goods.setGoodsId("GOODS-1");
            goods.setOrderId("ORDER-1");
            goods.setStatus(GoodsStatus.LOADED.getValue());
            OrderTaskMapper orders = proxy(OrderTaskMapper.class, (method, args) -> {
                if (method.equals("selectById")) return order;
                if (method.equals("update")) {
                    orderWrites++;
                    if (TaskStatus.DISPATCHED.getValue().equals(order.getStatus())) {
                        order.setStatus(TaskStatus.OUTBOUND.getValue());
                    } else if (TaskStatus.HANDOVER.getValue().equals(order.getStatus())) {
                        order.setStatus(TaskStatus.RETURNING.getValue());
                    } else {
                        etaWrites++;
                    }
                    return 1;
                }
                throw new UnsupportedOperationException(method);
            });
            GoodsMapper cargo = proxy(GoodsMapper.class, (method, args) -> {
                if (method.equals("selectList")) return List.of(goods);
                if (method.equals("update")) { goods.setStatus(GoodsStatus.DELIVERED.getValue()); return 1; }
                throw new UnsupportedOperationException(method);
            });
            TaskLogMapper taskLogs = proxy(TaskLogMapper.class, (method, args) -> {
                if (method.equals("insert")) { logs.add((TaskLogEntity) args[0]); return 1; }
                throw new UnsupportedOperationException(method);
            });
            service = new TelemetryTaskStateService(orders, taskLogs, cargo);
        }
    }

    private interface Handler { Object invoke(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
