package com.waterlogistics.collector.support;

import java.lang.reflect.Field;

/**
 * 反射注入 {@code @Value} 字段：单测不起 Spring 容器，按配置键语义直接赋值。
 */
public final class FieldInjector {

    private FieldInjector() {
    }

    public static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入字段 " + fieldName, e);
        }
    }

    /** 按 application-m1-sim.yml 的默认值填一遍，保证单测与线上默认行为一致。 */
    public static void applySimDefaults(Object target) {
        String cls = target.getClass().getSimpleName();
        switch (cls) {
            case "SimAisConnector" -> {
                set(target, "startLon", 114.300000);
                set(target, "startLat", 30.600000);
                set(target, "startCog", 90.0);
                set(target, "sogKnots", 8.0);
                set(target, "shipName", "SIM-SHIP-01");
                set(target, "mmsi", "123456789");
                set(target, "noise", 1e-9);          // 近似关噪声：ThreadLocalRandom.nextDouble(origin,bound) 要求 bound>origin
                set(target, "tickIntervalMillisConfig", 1000L);
            }
            case "SimUavTelemetryConnector" -> {
                set(target, "uavCount", 2);
                set(target, "centerLon", 114.305000);
                set(target, "centerLat", 30.605000);
                set(target, "radiusDeg", 0.0030);
                set(target, "phaseStep", 0.35);
                set(target, "speedMs", 12.0);
                set(target, "noise", 1e-9);
            }
            case "SimWeatherConnector" -> {
                set(target, "lon0", 114.290000);
                set(target, "lat0", 30.590000);
                set(target, "step", 0.010000);
                set(target, "grid", 2);
                set(target, "windBase", 5.0);
                set(target, "gustAdd", 3.0);
                set(target, "dirBase", 135.0);
                set(target, "windJitter", 1.5);
            }
            default -> throw new IllegalArgumentException("未登记的连接器：" + cls);
        }
    }
}
