package com.waterlogistics.monitor.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.storage.entity.FlightPointEntity;
import com.waterlogistics.storage.repository.FlightPointMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DatabaseFlightPointSinkTest {

    @Test
    void cleanedPoint_shouldPersistSystemCoordinatesAndFlightContext() {
        AtomicReference<FlightPointEntity> inserted = new AtomicReference<>();
        FlightPointMapper mapper = (FlightPointMapper) Proxy.newProxyInstance(
                FlightPointMapper.class.getClassLoader(),
                new Class<?>[]{FlightPointMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        inserted.set((FlightPointEntity) args[0]);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        DatabaseFlightPointSink sink = new DatabaseFlightPointSink(mapper);
        TelemetryState state = state(115.86123456, 28.66498765);
        FlightContextProvider.FlightContext context = new FlightContextProvider.FlightContext(
                "ORD-T2-9", "去程", null, null);

        sink.persist(state, context);

        FlightPointEntity point = inserted.get();
        assertEquals("UAV-T2-9", point.getDroneId());
        assertEquals("ORD-T2-9", point.getOrderId());
        assertEquals("去程", point.getSegmentType());
        assertEquals("115.8612346", point.getLon().toPlainString(), "经度按系统值保留七位，不做 GCJ 偏移");
        assertEquals("28.6649877", point.getLat().toPlainString());
        assertEquals("100.12", point.getAlt().toPlainString());
        assertEquals("12.35", point.getSpeed().toPlainString());
        assertEquals("90.12", point.getHeading().toPlainString());
    }

    @Test
    void pointWithoutFlightBinding_shouldStillPersistActualTelemetry() {
        AtomicReference<FlightPointEntity> inserted = new AtomicReference<>();
        FlightPointMapper mapper = (FlightPointMapper) Proxy.newProxyInstance(
                FlightPointMapper.class.getClassLoader(),
                new Class<?>[]{FlightPointMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        inserted.set((FlightPointEntity) args[0]);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });

        new DatabaseFlightPointSink(mapper).persist(state(115.86, 28.66), null);

        assertNull(inserted.get().getOrderId(), "无任务绑定时不伪造订单号");
        assertNull(inserted.get().getSegmentType(), "无任务绑定时不伪造航段");
    }

    private static TelemetryState state(double lon, double lat) {
        long ts = 1_700_000_000_000L;
        return new TelemetryState(
                new UavTelemetry("UAV-T2-9", lon, lat, 100.123, 12.345, 70.0, 90.123, ts),
                "ok", false, "good", List.of(), null, ts, "CGCS2000/WGS-84", Map.of());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
