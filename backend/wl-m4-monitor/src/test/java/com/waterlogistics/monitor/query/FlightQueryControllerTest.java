package com.waterlogistics.monitor.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.monitor.ingest.TelemetryService;
import com.waterlogistics.monitor.store.FlightRecordStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FlightQueryControllerTest {

    private static final TelemetryCleaner.Limits LIMITS =
            new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000);

    @Test
    void replay_shouldReturnChronologicalDroneStateFrames() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(2_000L, 70.0)));
        store.append(telemetry.acceptState(frame(1_000L, 80.0)));
        FlightQueryController controller = new FlightQueryController(new FlightQueryService(store));

        FlightQueryController.ReplayResponse response = controller.replay("UAV-D0-1");

        assertEquals("UAV-D0-1", response.taskId());
        assertEquals(2, response.frames().size());
        assertEquals("drone_state", response.frames().get(0).type());
        assertEquals(1_000L, response.frames().get(0).data().ts());
        assertEquals(90.0, response.frames().get(0).data().heading());
        assertNull(response.frames().get(0).data().activeOrderId());
        assertEquals(2_000L, response.frames().get(1).data().ts());
    }

    @Test
    void replay_shouldReturnNotFoundForUnknownTask() {
        FlightQueryController controller = new FlightQueryController(
                new FlightQueryService(new FlightRecordStore()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.replay("missing"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void statistics_shouldReportPersistedSamples() {
        FlightRecordStore store = new FlightRecordStore();
        TelemetryService telemetry = new TelemetryService(LIMITS, 4.5);
        store.append(telemetry.acceptState(frame(1_000L, 80.0)));
        FlightQueryController controller = new FlightQueryController(new FlightQueryService(store));

        assertEquals(1L, controller.statistics().get("flights"));
        assertEquals(1L, controller.statistics().get("samples"));
    }

    private static UavTelemetry frame(long ts, double battery) {
        return new UavTelemetry("UAV-D0-1", 115.90, 29.70, 100, 10, battery, 90, ts);
    }
}
