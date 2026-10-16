package com.waterlogistics.algo.measurement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.waterlogistics.contract.mq.UavTelemetry;

public final class TelemetryCleaner {
    public record Limits(double maxSpeedMs, double maxAltitudeM, double maxVerticalRateMs,
                         double maxAccelerationMs2, double maxTurnRateDegSec, long samplingMs) {
        public Limits {
            if (maxSpeedMs <= 0 || maxAltitudeM <= 0 || maxVerticalRateMs <= 0 || maxAccelerationMs2 <= 0 ||
                    maxTurnRateDegSec <= 0 || samplingMs <= 0) throw new IllegalArgumentException("Invalid limits");
        }
    }
    private final Limits limits;
    public TelemetryCleaner(Limits limits) { this.limits = limits; }

    public TelemetryState clean(UavTelemetry raw, TelemetryState lastReal) {
        return clean(raw, lastReal, List.of(), 4.5);
    }
    public TelemetryState clean(UavTelemetry raw, TelemetryState lastReal, List<Double> recentSpeeds, double madThreshold) {
        if (raw == null || raw.uavId() == null || raw.uavId().isBlank()) throw new IllegalArgumentException("uavId required");
        var flags = new ArrayList<String>();
        Map<String, Object> values = rawValues(raw);
        Double lat = finiteOrNull(raw.lat()), lon = finiteOrNull(raw.lon()), alt = finiteOrNull(raw.altM()), speed = finiteOrNull(raw.speedMs());
        Double track = finiteOrNull(raw.heading()), battery = finiteOrNull(raw.battery());
        if (!finiteRange(lat, -90, 90) || !finiteRange(lon, -180, 180)) { flags.add("POSITION_RANGE"); lat = null; lon = null; }
        if (!finiteRange(alt, 0, limits.maxAltitudeM())) { flags.add("ALTITUDE_RANGE"); alt = null; }
        if (!finiteRange(speed, 0, limits.maxSpeedMs())) { flags.add("SPEED_RANGE"); speed = null; }
        if (speed != null && madOutlier(speed, recentSpeeds, madThreshold)) { flags.add("SPEED_MAD_OUTLIER"); speed = null; }
        if (!finiteRange(track, 0, 360)) { flags.add("TRACK_RANGE"); track = null; } else if (track != null && track == 360) track = 0.0;
        if (!finiteRange(battery, 0, 100)) { flags.add("BATTERY_RANGE"); battery = null; }
        if (lastReal != null && raw.ts() <= lastReal.timestamp()) flags.add("NON_MONOTONIC_TIME");
        if (lastReal != null && lat != null && lon != null && lastReal.geometryUsable() && raw.ts() > lastReal.timestamp()) {
            double seconds = (raw.ts() - lastReal.timestamp()) / 1000.0;
            double implied = Geo.distance(lastReal.point(), new Geo.Point(lon, lat)) / seconds;
            if (implied > limits.maxSpeedMs()) { flags.add("POSITION_JUMP"); lat = null; lon = null; }
            if (speed != null && lastReal.groundSpeed() != null && Math.abs(speed - lastReal.groundSpeed()) / seconds > limits.maxAccelerationMs2()) {
                flags.add("ACCELERATION"); speed = null;
            }
            if (track != null && lastReal.track() != null && Math.abs((track - lastReal.track() + 540) % 360 - 180) / seconds > limits.maxTurnRateDegSec()) {
                flags.add("TURN_RATE"); track = null;
            }
        }
        boolean missing = lat == null || lon == null;
        boolean canFill = missing && lastReal != null && lastReal.geometryUsable() && lastReal.groundSpeed() != null && lastReal.track() != null &&
                raw.ts() > lastReal.timestamp() && raw.ts() - lastReal.timestamp() <= 2 * limits.samplingMs();
        if (canFill) {
            var p = Geo.destination(lastReal.point(), Math.toRadians(lastReal.track()), lastReal.groundSpeed() * (raw.ts() - lastReal.timestamp()) / 1000.0);
            lat = p.lat(); lon = p.lon(); flags.add("POSITION_IMPUTED");
        }
        // A filled battery remains visibly imputed so downstream energy decisions can reject it.
        boolean batteryFilled = battery == null && lastReal != null && lastReal.battery() != null &&
                raw.ts() > lastReal.timestamp() && raw.ts() - lastReal.timestamp() <= 2 * limits.samplingMs();
        if (batteryFilled) { battery = lastReal.battery(); flags.add("BATTERY_IMPUTED"); }
        boolean imputed = canFill || batteryFilled;
        String status = lat == null || lon == null ? "missing" : imputed ? "imputed_valid" : "cleaned_valid";
        var cleaned = new UavTelemetry(raw.uavId(), valueOrNaN(lon), valueOrNaN(lat), valueOrNaN(alt), valueOrNaN(speed),
                valueOrNaN(battery), valueOrNaN(track), raw.ts());
        return new TelemetryState(cleaned, status, imputed,
                status.equals("missing") ? "unusable" : imputed ? "estimated" : flags.isEmpty() ? "good" : "corrected",
                List.copyOf(flags), imputed ? "forward_extrapolation_or_last_valid_battery" : null,
                lastReal == null ? null : lastReal.timestamp(), "WGS84", values);
    }
    private static boolean finiteRange(Double v, double min, double max) { return v != null && Double.isFinite(v) && v >= min && v <= max; }
    private static Double finiteOrNull(double value) { return Double.isFinite(value) ? value : null; }
    private static double valueOrNaN(Double value) { return value == null ? Double.NaN : value; }
    public TelemetryState interpolate(TelemetryState left, TelemetryState right, long timestamp) {
        if (left == null || right == null || left.imputed() || right.imputed() || !left.geometryUsable() || !right.geometryUsable() ||
                !left.flightId().equals(right.flightId()) || timestamp <= left.timestamp() || timestamp >= right.timestamp() ||
                right.timestamp() - left.timestamp() > 3 * limits.samplingMs())
            throw new IllegalArgumentException("Offline interpolation requires two real observations within three intervals");
        double fraction = (timestamp - left.timestamp()) / (double) (right.timestamp() - left.timestamp());
        var point = Geo.interpolate(left.point(), right.point(), fraction);
        Double track = null;
        if (left.track() != null && right.track() != null) {
            double delta = (right.track() - left.track() + 540) % 360 - 180;
            track = (left.track() + fraction * delta + 360) % 360;
        }
        Double battery = linear(left.battery(), right.battery(), fraction);
        var telemetry = new UavTelemetry(left.flightId(), point.lon(), point.lat(), valueOrNaN(linear(left.altitude(), right.altitude(), fraction)),
                valueOrNaN(linear(left.groundSpeed(), right.groundSpeed(), fraction)), valueOrNaN(battery), valueOrNaN(track), timestamp);
        return new TelemetryState(telemetry,
                "imputed_valid", true, "estimated", List.of("OFFLINE_INTERPOLATED"), "great_circle_interpolation",
                left.timestamp(), "WGS84", Map.of("leftTimestamp", left.timestamp(), "rightTimestamp", right.timestamp()));
    }
    private static Double linear(Double a, Double b, double fraction) { return a == null || b == null ? null : a + (b - a) * fraction; }
    public static boolean madOutlier(double value, List<Double> samples, double threshold) {
        if (!Double.isFinite(value) || !Double.isFinite(threshold) || threshold <= 0) throw new IllegalArgumentException("Invalid MAD input");
        if (samples == null || samples.size() < 5) return false;
        var sorted = samples.stream().filter(x -> x != null && Double.isFinite(x)).sorted().toList();
        if (sorted.size() < 5) return false;
        double median = median(sorted);
        double mad = median(sorted.stream().map(x -> Math.abs(x - median)).sorted().toList());
        return mad > 1e-9 && 0.6745 * Math.abs(value - median) / mad > threshold;
    }
    private static double median(List<Double> sorted) {
        int n = sorted.size(); return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
    private static Map<String, Object> rawValues(UavTelemetry r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uavId", r.uavId()); m.put("lat", r.lat()); m.put("lon", r.lon()); m.put("altM", r.altM());
        m.put("speedMs", r.speedMs()); m.put("battery", r.battery()); m.put("heading", r.heading()); m.put("ts", r.ts());
        return m;
    }
}
