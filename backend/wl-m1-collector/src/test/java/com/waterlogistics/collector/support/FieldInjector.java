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

    /** 按仿真器代码默认值填一遍（T1-4 起为南昌航段），保证单测与线上默认行为一致。 */
    public static void applySimDefaults(Object target) {
        String cls = target.getClass().getSimpleName();
        switch (cls) {
            case "SimAisConnector" -> {
                set(target, "startLon", 115.861100);   // 演示基准定稿船起点（沿程 2.40 km，以放样为准）
                set(target, "startLat", 28.666000);
                set(target, "startCog", 31.2);         // 赣江航道南昌→八一大桥段走向（6.7° 朝陆地机巢，作废）
                set(target, "sogKnots", 8.0);
                set(target, "shipName", "SIM-SHIP-01");
                set(target, "mmsi", "123456789");
                set(target, "noise", 1e-9);          // 近似关噪声：ThreadLocalRandom.nextDouble(origin,bound) 要求 bound>origin
                set(target, "tickIntervalMillisConfig", 1000L);
            }
            case "SimUavTelemetryConnector" -> {
                set(target, "uavCount", 4);
                set(target, "southNestLon", 115.8480000);  // NEST-01
                set(target, "southNestLat", 28.6420000);
                set(target, "northNestLon", 115.8670000);  // NEST-02
                set(target, "northNestLat", 28.6980000);
                set(target, "radiusDeg", 0.00025);          // 约 27.8 m，小于偏航预警阈值 50 m
                set(target, "phaseStep", 0.43);             // 27.8 m × 0.43 ≈ 12 m/s，与 speed-ms 自洽
                set(target, "speedMs", 12.0);
                set(target, "noise", 1e-9);
            }
            case "SimWeatherConnector" -> {
                set(target, "lon0", 115.840000);       // 网格随定稿航段一起挪
                set(target, "lat0", 28.640000);
                set(target, "step", 0.058000);
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
