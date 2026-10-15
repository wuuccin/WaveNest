package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.FlightState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Demo storage port; replace with M6 repository when integrating the formal project. */
public final class FlightRecordStore {
    private final Map<String, List<FlightState.Cleaned>> records = new ConcurrentHashMap<>();
    public void append(FlightState.Cleaned state) {
        records.computeIfAbsent(state.flightId(), ignored -> new ArrayList<>());
        var list = records.get(state.flightId());
        synchronized (list) { list.add(state); }
    }
    public List<FlightState.Cleaned> replay(String flightId) {
        var list = records.get(flightId);
        if (list == null) return List.of();
        synchronized (list) { return list.stream().sorted(Comparator.comparingLong(FlightState.Cleaned::timestamp)).toList(); }
    }
    public List<String> flightIds() { return records.keySet().stream().sorted().toList(); }
    public Map<String, Object> statistics() {
        long flights = records.size(), samples = records.values().stream().mapToLong(List::size).sum();
        long estimated = records.values().stream().flatMap(List::stream).filter(FlightState.Cleaned::imputed).count();
        return Map.of("flights", flights, "samples", samples, "imputedSamples", estimated);
    }
}
