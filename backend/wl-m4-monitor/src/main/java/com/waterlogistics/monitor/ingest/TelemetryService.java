package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.mq.UavTelemetry;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** MQ consumer owned by M4's telemetry ingress calls this service once per normalized sample. */
public final class TelemetryService {
    private final TelemetryCleaner cleaner;
    private final Map<String, TelemetryState> lastReal = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> speedWindows = new ConcurrentHashMap<>();
    private final double madThreshold;
    public TelemetryService(TelemetryCleaner.Limits limits, double madThreshold) { this.cleaner = new TelemetryCleaner(limits); this.madThreshold = madThreshold; }
    /** Accepts and returns the module contract object used on the MQ boundary. */
    public synchronized UavTelemetry accept(UavTelemetry raw) {
        return acceptState(raw).telemetry();
    }

    /** Internal M4 view retaining quality and provenance for tracking/storage. */
    public synchronized TelemetryState acceptState(UavTelemetry raw) {
        var window = speedWindows.computeIfAbsent(raw.uavId(), ignored -> new ArrayDeque<>());
        TelemetryState previous = lastReal.get(raw.uavId());
        TelemetryState result = cleaner.clean(raw, previous, new ArrayList<>(window), madThreshold);
        if (!result.imputed() && result.groundSpeed() != null) { window.addLast(result.groundSpeed()); if (window.size() > 15) window.removeFirst(); }
        if (!result.imputed() && result.geometryUsable() && (previous == null || result.timestamp() > previous.timestamp()))
            lastReal.put(raw.uavId(), result);
        return result;
    }
}
