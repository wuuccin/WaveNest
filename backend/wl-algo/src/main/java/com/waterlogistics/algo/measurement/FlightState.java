package com.waterlogistics.algo.measurement;

import java.util.List;
import java.util.Map;

public final class FlightState {
    private FlightState() {}
    public record Raw(String flightId, long timestamp, Double latitude, Double longitude, Double altitude,
                      Double groundSpeed, Double track, Double verticalRate, Double battery) {}
    public record Cleaned(String flightId, long timestamp, Double latitude, Double longitude, Double altitude,
                          Double groundSpeed, Double track, Double verticalRate, Double battery,
                          String stateStatus, boolean imputed, String stateQuality, List<String> anomalyFlags,
                          String imputationMethod, Long sourceTimestamp, String coordinateSystem,
                          Map<String, Object> rawValues) {
        public boolean geometryUsable() { return latitude != null && longitude != null && !"missing".equals(stateStatus); }
        public Geo.Point point() { return new Geo.Point(longitude, latitude); }
    }
}
