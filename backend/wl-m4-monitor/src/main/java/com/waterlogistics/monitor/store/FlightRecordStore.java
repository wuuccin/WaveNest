package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.TelemetryState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Task-scoped observed telemetry history. Estimated points remain marked. */
public final class FlightRecordStore {
    public record TaskSummary(String taskId, String orderId, String droneId, int samples, long latestTs) {}
    private record TaskMeta(String orderId, String droneId) {}
    private final Map<String, TaskMeta> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<TelemetryState>> records = new ConcurrentHashMap<>();
    public void register(String taskId, String orderId, String droneId) {
        if (taskId == null || taskId.isBlank() || droneId == null || droneId.isBlank())
            throw new IllegalArgumentException("taskId and droneId required");
        tasks.put(taskId, new TaskMeta(orderId, droneId));
        records.computeIfAbsent(taskId, ignored -> new ArrayList<>());
    }
    /** Compatibility for standalone use; live ingress must supply a task ID. */
    public void append(TelemetryState state) { append(state.flightId(), state); }
    public void append(String taskId, TelemetryState state) {
        if (taskId == null || state == null) throw new IllegalArgumentException("task and state required");
        var meta = tasks.get(taskId);
        if (meta != null && !meta.droneId().equals(state.flightId()))
            throw new IllegalArgumentException("Telemetry drone does not match task plan");
        var list = records.computeIfAbsent(taskId, ignored -> new ArrayList<>());
        synchronized (list) { list.add(state); }
    }
    public List<TelemetryState> replay(String taskId) {
        var list = records.get(taskId);
        if (list == null) return List.of();
        synchronized (list) { return list.stream().sorted(Comparator.comparingLong(TelemetryState::timestamp)).toList(); }
    }
    public List<TaskSummary> tasks() {
        return tasks.entrySet().stream().map(entry -> {
            var samples = replay(entry.getKey());
            return new TaskSummary(entry.getKey(), entry.getValue().orderId(), entry.getValue().droneId(),
                    samples.size(), samples.isEmpty() ? 0 : samples.get(samples.size() - 1).timestamp());
        }).sorted(Comparator.comparingLong(TaskSummary::latestTs).reversed()).toList();
    }
    public List<String> flightIds() { return records.keySet().stream().sorted().toList(); }
    public Map<String, Object> statistics() {
        var samples = records.values().stream().flatMap(list -> {
            synchronized (list) { return List.copyOf(list).stream(); }
        }).toList();
        return Map.of("flights", records.size(), "samples", samples.size(),
                "imputedSamples", samples.stream().filter(TelemetryState::imputed).count());
    }
}
