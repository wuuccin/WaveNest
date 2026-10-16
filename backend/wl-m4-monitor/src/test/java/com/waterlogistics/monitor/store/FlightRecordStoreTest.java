package com.waterlogistics.monitor.store;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.monitor.ingest.TelemetryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightRecordStoreTest {
    @Test
    void taskHistoryKeepsCleanedSamplesInTimeOrderAndRejectsOtherDrone() {
        var store = new FlightRecordStore();
        store.register("TASK-1", "ORDER-1", "U1");
        var ingress = new TelemetryService(new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000), 4.5);
        var first = ingress.acceptState(new UavTelemetry("U1", 115.8, 29.7, 100, 10, 88, 90, 1000));
        var estimated = ingress.acceptState(new UavTelemetry("U1", Double.NaN, Double.NaN, 100, 10, 88, 90, 2000));
        store.append("TASK-1", estimated);
        store.append("TASK-1", first);

        assertEquals(1000, store.replay("TASK-1").get(0).timestamp());
        assertTrue(store.replay("TASK-1").get(1).imputed());
        assertEquals("ORDER-1", store.tasks().get(0).orderId());
        assertEquals(2, store.tasks().get(0).samples());
        assertEquals(1L, store.statistics().get("imputedSamples"));
        var otherDrone = new TelemetryService(new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000), 4.5)
                .acceptState(new UavTelemetry("U2", 115.8, 29.7, 100, 10, 88, 90, 1000));
        assertThrows(IllegalArgumentException.class, () -> store.append("TASK-1", otherDrone));
    }
}
