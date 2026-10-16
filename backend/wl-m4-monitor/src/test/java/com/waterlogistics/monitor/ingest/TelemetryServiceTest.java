package com.waterlogistics.monitor.ingest;

import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.algo.measurement.TelemetryCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryServiceTest {
    private static final TelemetryCleaner.Limits LIMITS =
            new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000);

    @Test
    void ingressUsesContractTelemetryAndReturnsContractTelemetry() {
        var service = new TelemetryService(LIMITS, 4.5);
        var first = new UavTelemetry("U1", 115.8, 29.7, 100, 10, 88, 90, 1000);
        var missingPosition = new UavTelemetry("U1", Double.NaN, Double.NaN, 100, 10, 88, 90, 2000);

        assertEquals(first, service.accept(first));
        var state = service.acceptState(missingPosition);
        assertTrue(state.imputed());
        assertEquals(88, state.telemetry().battery());
        assertTrue(Double.isFinite(state.telemetry().lon()));
        assertTrue(Double.isFinite(state.telemetry().lat()));
    }

    @Test
    void rejectsDuplicateAndOutOfOrderTelemetryWithoutChangingLatestSample() {
        var service = new TelemetryService(LIMITS, 4.5);
        var first = new UavTelemetry("U1", 115.8, 29.7, 100, 10, 88, 90, 2000);
        service.acceptState(first);
        assertThrows(IllegalArgumentException.class, () -> service.acceptState(first));
        assertThrows(IllegalArgumentException.class, () -> service.acceptState(
                new UavTelemetry("U1", 115.8, 29.7, 100, 10, 88, 90, 1000)));
        var next = service.acceptState(new UavTelemetry("U1", 115.8001, 29.7, 100, 10, 87, 90, 3000));
        assertFalse(next.anomalyFlags().contains("NON_MONOTONIC_TIME"));
    }

    @Test
    void keepsRealPositionWhenOnlyBatteryIsImputed() {
        var service = new TelemetryService(LIMITS, 4.5);
        service.acceptState(new UavTelemetry("U1", 115.8, 29.7, 100, 10, 88, 90, 1000));
        var state = service.acceptState(new UavTelemetry("U1", 115.8001, 29.7, 100, 10,
                Double.NaN, 90, 2000));
        assertTrue(state.geometryUsable());
        assertTrue(state.anomalyFlags().contains("BATTERY_IMPUTED"));
        assertFalse(state.anomalyFlags().contains("POSITION_IMPUTED"));
    }
}
