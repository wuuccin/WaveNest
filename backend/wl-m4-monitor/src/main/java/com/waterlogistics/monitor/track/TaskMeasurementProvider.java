package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.mq.TaskPlanMessage;
import com.waterlogistics.monitor.store.FlightRecordStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Routes come only from M2 task.plan; no M3 direct read or inferred route version. */
public final class TaskMeasurementProvider implements MeasurementProvider {
    private final FlightTracker tracker = new FlightTracker();
    private final FlightRecordStore records;
    private final Map<String, TaskPlanMessage> byDrone = new ConcurrentHashMap<>();
    private final Map<String, Long> routeRevisions = new ConcurrentHashMap<>();
    public TaskMeasurementProvider(FlightRecordStore records) { this.records = records; }
    public void acceptPlan(TaskPlanMessage plan) {
        if (plan == null || plan.taskId() == null || plan.droneId() == null) throw new IllegalArgumentException("Task plan identifiers required");
        var existing = byDrone.get(plan.droneId());
        if (existing != null && existing.taskId().equals(plan.taskId()) &&
                !existing.routeVersion().equals(plan.routeVersion()))
            routeRevisions.merge(plan.taskId(), 1L, Long::sum);
        else routeRevisions.putIfAbsent(plan.taskId(), 0L);
        byDrone.put(plan.droneId(), plan);
        records.register(plan.taskId(), plan.orderId(), plan.droneId());
    }
    public TaskPlanMessage planFor(String droneId) { return byDrone.get(droneId); }
    @Override public FlightTracker.Measurement measure(TelemetryState state) {
        var plan = byDrone.get(state.flightId());
        if (plan == null || plan.waypoints() == null || plan.waypoints().size() < 2) return null;
        List<Geo.Point> points = plan.waypoints().stream().map(p -> new Geo.Point(p.lon(), p.lat())).toList();
        var route = new RouteMeasurement.Route(plan.taskId(), routeRevisions.getOrDefault(plan.taskId(), 0L), points);
        // The numeric revision is local to the measurement API; the authoritative string remains in task.plan.
        return tracker.measure(state, route, null, false);
    }
}
