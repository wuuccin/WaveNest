package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.RouteMeasurement;

/**
 * Resolves the current dispatched flight for one telemetry source.
 *
 * <p>The route is deliberately the T2 straight baseline from the drone's home
 * nest to the committed rendezvous point. It is an interim off-track reference,
 * not the future planned-flight polyline.</p>
 */
public interface FlightContextProvider {

    FlightContextProvider NONE = droneId -> null;

    FlightContext resolve(String droneId);

    record FlightContext(
            String orderId,
            String segmentType,
            RouteMeasurement.Route route,
            RouteMeasurement.Landing landing) {
    }
}
