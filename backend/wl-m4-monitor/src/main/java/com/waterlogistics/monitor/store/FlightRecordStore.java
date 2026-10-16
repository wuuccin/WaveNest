package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.TelemetryState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Demo storage port; replace with M6 repository when integrating the formal project. */
public final class FlightRecordStore {
    private final Map<String, List<TelemetryState>> records = new ConcurrentHashMap<>();
    public void append(TelemetryState state) {
        records.computeIfAbsent(state.flightId(), ignored -> new ArrayList<>());
        var list = records.get(state.flightId());
        synchronized (list) { list.add(state); }
    }
    public List<TelemetryState> replay(String flightId) {
        var list = records.get(flightId);
        if (list == null) return List.of();
        synchronized (list) { return list.stream().sorted(Comparator.comparingLong(TelemetryState::timestamp)).toList(); }
    }
    public List<String> flightIds() { return records.keySet().stream().sorted().toList(); }
    public Map<String, Object> statistics() {
        long flights = records.size(), samples = records.values().stream().mapToLong(List::size).sum();
        long estimated = records.values().stream().flatMap(List::stream).filter(TelemetryState::imputed).count();
        return Map.of("flights", flights, "samples", samples, "imputedSamples", estimated);
    }
}
