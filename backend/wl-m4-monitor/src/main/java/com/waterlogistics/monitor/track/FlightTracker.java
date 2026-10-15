package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.FlightState;
import com.waterlogistics.algo.measurement.RouteMeasurement;

public final class FlightTracker {
    public record Measurement(RouteMeasurement.Projection projection, RouteMeasurement.Progress progress,
                              RouteMeasurement.Remaining remaining) {}
    public Measurement measure(FlightState.Cleaned state, RouteMeasurement.Route route,
                               RouteMeasurement.Landing landing, boolean returningOrOffRoute) {
        if (!state.geometryUsable() || route == null) return null;
        var projection = RouteMeasurement.project(route, state.point(), state.timestamp());
        return new Measurement(projection,
                RouteMeasurement.progress(route, projection, state.imputed(), returningOrOffRoute),
                RouteMeasurement.remaining(route, projection, landing, returningOrOffRoute, state.imputed()));
    }
}
