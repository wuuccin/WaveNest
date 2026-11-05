package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.algo.measurement.TelemetryState;
import org.springframework.stereotype.Component;

@Component
public final class FlightTracker {
    public record Measurement(RouteMeasurement.Projection projection, RouteMeasurement.Progress progress,
                              RouteMeasurement.Remaining remaining) {}
    public Measurement measure(TelemetryState state, RouteMeasurement.Route route,
                               RouteMeasurement.Landing landing, boolean returningOrOffRoute) {
        if (!state.geometryUsable() || route == null) return null;
        var projection = RouteMeasurement.project(route, state.telemetry());
        return new Measurement(projection,
                RouteMeasurement.progress(route, projection, state.imputed(), returningOrOffRoute),
                RouteMeasurement.remaining(route, projection, landing, returningOrOffRoute, state.imputed()));
    }
}
