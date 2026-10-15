package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.measurement.FlightState;
import com.waterlogistics.algo.measurement.TelemetryCleaner;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** MQ consumer owned by M4's telemetry ingress calls this service once per normalized sample. */
public final class TelemetryService {
    private final TelemetryCleaner cleaner;
    private final Map<String, FlightState.Cleaned> lastReal = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> speedWindows = new ConcurrentHashMap<>();
    private final double madThreshold;
    public TelemetryService(TelemetryCleaner.Limits limits, double madThreshold) { this.cleaner = new TelemetryCleaner(limits); this.madThreshold = madThreshold; }
    public synchronized FlightState.Cleaned accept(FlightState.Raw raw) {
        FlightState.Cleaned previous = lastReal.get(raw.flightId());
        var window = speedWindows.computeIfAbsent(raw.flightId(), ignored -> new ArrayDeque<>());
        FlightState.Cleaned result = cleaner.clean(raw, previous, new ArrayList<>(window), madThreshold);
        if (!result.imputed() && result.groundSpeed() != null) { window.addLast(result.groundSpeed()); if (window.size() > 15) window.removeFirst(); }
        if (!result.imputed() && result.geometryUsable() && (previous == null || result.timestamp() > previous.timestamp()))
            lastReal.put(raw.flightId(), result);
        return result;
    }
}
