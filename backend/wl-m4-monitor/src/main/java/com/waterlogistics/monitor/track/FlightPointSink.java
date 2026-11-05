package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.TelemetryState;

/** Persistence boundary for cleaned actual-flight telemetry. */
public interface FlightPointSink {

    FlightPointSink NONE = (state, context) -> { };

    void persist(TelemetryState state, FlightContextProvider.FlightContext context);
}
