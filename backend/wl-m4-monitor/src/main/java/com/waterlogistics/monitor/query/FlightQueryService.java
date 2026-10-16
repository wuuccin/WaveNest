package com.waterlogistics.monitor.query;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.monitor.store.FlightRecordStore;
import java.util.List;
import java.util.Map;

public final class FlightQueryService {
    private final FlightRecordStore store;
    public FlightQueryService(FlightRecordStore store) { this.store = store; }
    public List<String> tasks() { return store.flightIds(); }
    public List<TelemetryState> replay(String taskId) { return store.replay(taskId); }
    public Map<String, Object> statistics() { return store.statistics(); }
}
