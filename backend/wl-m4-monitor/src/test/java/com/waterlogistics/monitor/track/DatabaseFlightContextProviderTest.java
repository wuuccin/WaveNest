package com.waterlogistics.monitor.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class DatabaseFlightContextProviderTest {

    static {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "flight-context-test");
        TableInfoHelper.initTableInfo(assistant, OrderTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskLogEntity.class);
    }

    @Test
    void dispatchedCycle_shouldResolveStraightBaselineFromHomeNestToMeeting() {
        FlightContextProvider.FlightContext context = provider(List.of(log(8L, "派发", "已派发")))
                .resolve("UAV-1");

        assertEquals("ORD-1", context.orderId());
        assertEquals("去程", context.segmentType());
        assertEquals("straight:ORD-1", context.route().routeId());
        assertEquals(115.850, context.route().waypoints().get(0).lon(), 1e-9);
        assertEquals(28.690, context.route().waypoints().get(1).lat(), 1e-9);
    }

    @Test
    void completedCycle_shouldNotBindStaleDispatchedOrder() {
        DatabaseFlightContextProvider provider = provider(List.of(
                log(10L, "状态流转", "已完成"),
                log(8L, "派发", "已派发")));

        assertNull(provider.resolve("UAV-1"));
    }

    @Test
    void returningCycle_shouldPersistReturnSegmentAfterLatestDispatch() {
        FlightContextProvider.FlightContext context = provider(List.of(
                log(10L, "状态流转", "返航中"),
                log(8L, "派发", "已派发"))).resolve("UAV-1");

        assertEquals("返程", context.segmentType());
    }

    private static DatabaseFlightContextProvider provider(List<TaskLogEntity> logs) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("ORD-1");
        order.setDroneId("UAV-1");
        order.setStatus("已派发");
        order.setMeetLon(new BigDecimal("115.880"));
        order.setMeetLat(new BigDecimal("28.690"));
        order.setMeetTime(LocalDateTime.of(2026, 10, 28, 12, 0));

        DroneEntity drone = new DroneEntity();
        drone.setDroneId("UAV-1");
        drone.setHomeNestId("NEST-1");

        NestEntity nest = new NestEntity();
        nest.setNestId("NEST-1");
        nest.setLon(new BigDecimal("115.850"));
        nest.setLat(new BigDecimal("28.640"));

        return new DatabaseFlightContextProvider(
                proxy(OrderTaskMapper.class, "selectList", List.of(order), null),
                proxy(TaskLogMapper.class, "selectList", logs, null),
                proxy(DroneMapper.class, "selectById", drone, null),
                proxy(NestMapper.class, "selectById", nest, null));
    }

    private static TaskLogEntity log(long id, String opType, String toStatus) {
        TaskLogEntity log = new TaskLogEntity();
        log.setLogId(id);
        log.setOrderId("ORD-1");
        log.setOpType(opType);
        log.setToStatus(toStatus);
        return log;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, String methodName, Object result, Object defaultResult) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> methodName.equals(method.getName()) ? result : defaultValue(method.getReturnType(), defaultResult));
    }

    private static Object defaultValue(Class<?> type, Object value) {
        if (!type.isPrimitive()) return value;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
