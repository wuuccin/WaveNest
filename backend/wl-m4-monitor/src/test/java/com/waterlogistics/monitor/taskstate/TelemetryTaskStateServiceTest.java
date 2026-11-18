package com.waterlogistics.monitor.taskstate;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.state.TaskStatus;
import com.waterlogistics.monitor.track.FlightContextProvider;
import com.waterlogistics.monitor.track.FlightTracker;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class TelemetryTaskStateServiceTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void cleanedTelemetryUpdatesProgressAndEta() {
        Fixture fixture = new Fixture(TaskStatus.DISPATCHED);
        fixture.goods.setStatus(GoodsStatus.READY.getValue());
        fixture.order.setGoodsType("应急药品");
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
        assertEquals(GoodsStatus.LOADED.getValue(), fixture.goods.getStatus(),
                "遥测确认起飞须同步完成装机，后续回收才能消费合法前态");
        assertEquals(1, fixture.logs.size());
        assertEquals(1, fixture.etaWrites);
        // 起飞必须同时回写无人机态并广播订单视图：只落库不广播，界面会停在「已派发」
        assertEquals(1, fixture.droneWrites);
        OrderStateChangedEvent event = assertInstanceOf(OrderStateChangedEvent.class, fixture.published.get(0));
        assertEquals(OrderStatus.OUTBOUND, event.view().orderStatus());
        assertEquals("ORDER-1", event.view().orderId());
        assertEquals(2L, event.view().stateVersion());
        assertEquals("应急药品", event.view().goodsType(), "遥测推进的状态帧必须带上配送品类，否则界面把已展示的品类退回「未记录」");
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
        assertEquals(1, fixture.droneWrites);
        OrderStateChangedEvent event = assertInstanceOf(OrderStateChangedEvent.class, fixture.published.get(0));
        assertEquals(OrderStatus.RETURNING, event.view().orderStatus());
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
        static {
            // LambdaUpdateWrapper 需要实体的列缓存；桩里没有 MyBatis 上下文，手工注册一次
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), DroneEntity.class);
        }

        final OrderTaskEntity order = new OrderTaskEntity();
        final GoodsEntity goods = new GoodsEntity();
        final List<TaskLogEntity> logs = new ArrayList<>();
        final List<Object> published = new ArrayList<>();
        final TelemetryTaskStateService service;
        int orderWrites;
        int etaWrites;
        int droneWrites;

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
                if (method.equals("update")) {
                    goods.setStatus(GoodsStatus.READY.getValue().equals(goods.getStatus())
                            ? GoodsStatus.LOADED.getValue() : GoodsStatus.DELIVERED.getValue());
                    return 1;
                }
                throw new UnsupportedOperationException(method);
            });
            TaskLogMapper taskLogs = proxy(TaskLogMapper.class, (method, args) -> {
                if (method.equals("insert")) {
                    TaskLogEntity entry = (TaskLogEntity) args[0];
                    // 真库由自增主键回填 log_id；桩里按插入顺序补，版本号才有来源
                    entry.setLogId((long) logs.size() + 1);
                    logs.add(entry);
                    return 1;
                }
                throw new UnsupportedOperationException(method);
            });
            DroneMapper drones = proxy(DroneMapper.class, (method, args) -> {
                if (method.equals("update")) { droneWrites++; return 1; }
                throw new UnsupportedOperationException(method);
            });
            ApplicationEventPublisher events = proxy(ApplicationEventPublisher.class, (method, args) -> {
                if (method.equals("publishEvent")) { published.add(args[0]); return null; }
                throw new UnsupportedOperationException(method);
            });
            service = new TelemetryTaskStateService(orders, taskLogs, cargo, drones, events);
        }
    }

    private interface Handler { Object invoke(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }
}
