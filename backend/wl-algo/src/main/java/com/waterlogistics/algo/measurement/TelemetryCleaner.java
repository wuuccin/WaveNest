package com.waterlogistics.algo.measurement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.waterlogistics.algo.measurement.FlightState.*;

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

    public Cleaned clean(Raw raw, Cleaned lastReal) {
        return clean(raw, lastReal, List.of(), 4.5);
    }
    public Cleaned clean(Raw raw, Cleaned lastReal, List<Double> recentSpeeds, double madThreshold) {
        if (raw == null || raw.flightId() == null || raw.flightId().isBlank()) throw new IllegalArgumentException("flightId required");
        var flags = new ArrayList<String>();
        Map<String, Object> values = rawValues(raw);
        Double lat = raw.latitude(), lon = raw.longitude(), alt = raw.altitude(), speed = raw.groundSpeed();
        Double track = raw.track(), vertical = raw.verticalRate(), battery = raw.battery();
        if (!finiteRange(lat, -90, 90) || !finiteRange(lon, -180, 180)) { flags.add("POSITION_RANGE"); lat = null; lon = null; }
        if (!finiteRange(alt, 0, limits.maxAltitudeM())) { flags.add("ALTITUDE_RANGE"); alt = null; }
        if (!finiteRange(speed, 0, limits.maxSpeedMs())) { flags.add("SPEED_RANGE"); speed = null; }
        if (speed != null && madOutlier(speed, recentSpeeds, madThreshold)) { flags.add("SPEED_MAD_OUTLIER"); speed = null; }
        if (!finiteRange(track, 0, 360)) { flags.add("TRACK_RANGE"); track = null; } else if (track != null && track == 360) track = 0.0;
        if (!finiteRange(vertical, -limits.maxVerticalRateMs(), limits.maxVerticalRateMs())) { flags.add("VERTICAL_RATE_RANGE"); vertical = null; }
        if (!finiteRange(battery, 0, 100)) { flags.add("BATTERY_RANGE"); battery = null; }
        if (lastReal != null && raw.timestamp() <= lastReal.timestamp()) flags.add("NON_MONOTONIC_TIME");
        if (lastReal != null && lat != null && lon != null && lastReal.geometryUsable() && raw.timestamp() > lastReal.timestamp()) {
            double seconds = (raw.timestamp() - lastReal.timestamp()) / 1000.0;
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
                raw.timestamp() > lastReal.timestamp() && raw.timestamp() - lastReal.timestamp() <= 2 * limits.samplingMs();
        if (canFill) {
            var p = Geo.destination(lastReal.point(), Math.toRadians(lastReal.track()), lastReal.groundSpeed() * (raw.timestamp() - lastReal.timestamp()) / 1000.0);
            lat = p.lat(); lon = p.lon(); flags.add("POSITION_IMPUTED");
        }
        // A filled battery remains visibly imputed so downstream energy decisions can reject it.
        boolean batteryFilled = battery == null && lastReal != null && lastReal.battery() != null &&
                raw.timestamp() > lastReal.timestamp() && raw.timestamp() - lastReal.timestamp() <= 2 * limits.samplingMs();
        if (batteryFilled) { battery = lastReal.battery(); flags.add("BATTERY_IMPUTED"); }
        boolean imputed = canFill || batteryFilled;
        String status = lat == null || lon == null ? "missing" : imputed ? "imputed_valid" : "cleaned_valid";
        return new Cleaned(raw.flightId(), raw.timestamp(), lat, lon, alt, speed, track, vertical, battery,
                status, imputed, status.equals("missing") ? "unusable" : imputed ? "estimated" : flags.isEmpty() ? "good" : "corrected",
                List.copyOf(flags), imputed ? "forward_extrapolation_or_last_valid_battery" : null,
                lastReal == null ? null : lastReal.timestamp(), "WGS84", values);
    }
    private static boolean finiteRange(Double v, double min, double max) { return v != null && Double.isFinite(v) && v >= min && v <= max; }
    public Cleaned interpolate(Cleaned left, Cleaned right, long timestamp) {
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
        return new Cleaned(left.flightId(), timestamp, point.lat(), point.lon(),
                linear(left.altitude(), right.altitude(), fraction), linear(left.groundSpeed(), right.groundSpeed(), fraction),
                track, linear(left.verticalRate(), right.verticalRate(), fraction), battery,
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
    private static Map<String, Object> rawValues(Raw r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("latitude", r.latitude()); m.put("longitude", r.longitude()); m.put("altitude", r.altitude());
        m.put("ground_speed", r.groundSpeed()); m.put("track", r.track()); m.put("vertical_rate", r.verticalRate()); m.put("battery", r.battery());
        return m;
    }
}
