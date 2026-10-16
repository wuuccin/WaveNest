package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.TelemetryState;

/** Shared M4 measurement surface; detection consumes this instead of reprojecting telemetry. */
public interface MeasurementProvider {
    FlightTracker.Measurement measure(TelemetryState state);
}
