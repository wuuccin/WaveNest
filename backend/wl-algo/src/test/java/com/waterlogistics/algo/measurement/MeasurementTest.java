package com.waterlogistics.algo.measurement;

import static org.junit.jupiter.api.Assertions.*;
import static com.waterlogistics.algo.measurement.RouteMeasurement.*;
import com.waterlogistics.contract.mq.UavTelemetry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeasurementTest {
    private static final TelemetryCleaner.Limits LIMITS = new TelemetryCleaner.Limits(50, 5000, 15, 20, 30, 1000);
    @Test void batteryOutlierIsFlaggedAndFilledWithProvenance() {
        var cleaner = new TelemetryCleaner(LIMITS);
        var previous = cleaner.clean(new UavTelemetry("F1", 115.8, 29.7, 100.0, 10.0, 88.0, 90.0, 1000), null);
        var next = cleaner.clean(new UavTelemetry("F1", 115.8001, 29.7, 100.0, 10.0, 140.0, 90.0, 2000), previous);
        assertEquals(88, next.battery()); assertTrue(next.imputed());
        assertEquals(140.0, next.rawValues().get("battery")); assertTrue(next.anomalyFlags().contains("BATTERY_RANGE"));
        assertTrue(next.anomalyFlags().contains("BATTERY_IMPUTED"));
    }
    @Test void longGapIsMissingAndNeverChainedFromImputedPoint() {
        var cleaner = new TelemetryCleaner(LIMITS);
        var real = cleaner.clean(new UavTelemetry("F1", 115.8, 29.7, 100.0, 10.0, 88.0, 90.0, 1000), null);
        var shortGap = cleaner.clean(new UavTelemetry("F1", Double.NaN, Double.NaN, 100.0, 10.0, Double.NaN, 90.0, 2000), real);
        assertEquals("imputed_valid", shortGap.stateStatus());
        var longGap = cleaner.clean(new UavTelemetry("F1", Double.NaN, Double.NaN, 100.0, 10.0, Double.NaN, 90.0, 4001), real);
        assertEquals("missing", longGap.stateStatus());
    }
    @Test void projectionAndRemainingDistanceFollowRouteNotDirectLine() {
        var route = new Route("R1", 1, List.of(new Geo.Point(0,0),new Geo.Point(1,0),new Geo.Point(1,1)));
        var p = project(route,new Geo.Point(.5,.1),10);
        assertEquals(0,p.nearestSegmentIndex()); assertTrue(p.deviationDistance()>10000 && p.deviationDistance()<12000);
        var progress = progress(route,p,false,false);
        assertTrue(progress.fraction()>.2 && progress.fraction()<.3); assertTrue(progress.arrivalConfirmable());
        var landing = new Landing("POINT","P1",new Geo.Point(1,1.1),2,null);
        var remaining = remaining(route,p,landing,false,false);
        assertTrue(remaining.computable()); assertTrue(remaining.distanceM()>160000);
        assertFalse(remaining(route,p,landing,true,false).computable());
    }
    @Test void datelineAndZeroLengthSegmentRemainFinite() {
        var route = new Route("R2", 3, List.of(new Geo.Point(179.9,0),new Geo.Point(179.9,0),new Geo.Point(-179.9,0)));
        var p = project(route,new Geo.Point(180,0.01),20);
        assertTrue(Double.isFinite(p.deviationDistance())); assertTrue(p.deviationDistance()<2000);
        assertThrows(IllegalArgumentException.class,()->progress(new Route("R2",4,route.waypoints()),p,false,false));
    }
    @Test void interpolationHandlesHeadingWrapAndMarksBatteryEstimated() {
        var cleaner = new TelemetryCleaner(LIMITS);
        var left = cleaner.clean(new UavTelemetry("F1",179.9,0.0,100.0,10.0,90.0,350.0,1000),null);
        var right = cleaner.clean(new UavTelemetry("F1",-179.9,0.0,100.0,10.0,80.0,10.0,3000),null);
        var middle = cleaner.interpolate(left,right,2000);
        assertTrue(Math.abs(middle.longitude()) > 179.9); assertEquals(85,middle.battery());
        assertEquals(0,middle.track(),1e-6); assertTrue(middle.imputed());
        assertThrows(IllegalArgumentException.class,()->cleaner.interpolate(left,right,4000));
    }
    @Test void madUsesMedianAndProtectsZeroDispersion() {
        assertTrue(TelemetryCleaner.madOutlier(30,List.of(9.0,9.5,10.0,10.5,11.0),4.5));
        assertFalse(TelemetryCleaner.madOutlier(11,List.of(10.0,10.0,10.0,10.0,10.0),4.5));
    }
}
