package com.waterlogistics.algo.measurement;

import com.waterlogistics.contract.mq.UavTelemetry;
import java.util.List;
import java.util.Map;

/**
 * M4 的清洗结果。线上遥测字段始终使用契约 {@link UavTelemetry}；本对象只承载
 * 清洗状态和追溯信息，避免再复制一份模块层遥测模型。
 */
public record TelemetryState(
        UavTelemetry telemetry,
        String stateStatus,
        boolean imputed,
        String stateQuality,
        List<String> anomalyFlags,
        String imputationMethod,
        Long sourceTimestamp,
        String coordinateSystem,
        Map<String, Object> rawValues) {

    public TelemetryState {
        if (telemetry == null) throw new IllegalArgumentException("telemetry required");
        anomalyFlags = anomalyFlags == null ? List.of() : List.copyOf(anomalyFlags);
        rawValues = rawValues == null ? Map.of() : Map.copyOf(rawValues);
    }

    public String flightId() { return telemetry.uavId(); }
    public long timestamp() { return telemetry.ts(); }
    public Double latitude() { return finiteOrNull(telemetry.lat()); }
    public Double longitude() { return finiteOrNull(telemetry.lon()); }
    public Double altitude() { return finiteOrNull(telemetry.altM()); }
    public Double groundSpeed() { return finiteOrNull(telemetry.speedMs()); }
    public Double track() { return finiteOrNull(telemetry.heading()); }
    public Double battery() { return finiteOrNull(telemetry.battery()); }
    public boolean geometryUsable() { return latitude() != null && longitude() != null && !"missing".equals(stateStatus); }
    public Geo.Point point() { return new Geo.Point(telemetry.lon(), telemetry.lat()); }

    private static Double finiteOrNull(double value) { return Double.isFinite(value) ? value : null; }
}
