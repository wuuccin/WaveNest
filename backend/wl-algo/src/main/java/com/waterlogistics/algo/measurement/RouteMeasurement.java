package com.waterlogistics.algo.measurement;

import com.waterlogistics.contract.mq.UavTelemetry;
import java.util.List;

public final class RouteMeasurement {
    private RouteMeasurement() {}
    public record Route(String routeId, long version, List<Geo.Point> waypoints) {
        public Route {
            if (routeId == null || routeId.isBlank() || version < 0 || waypoints == null || waypoints.size() < 2)
                throw new IllegalArgumentException("Route id, version and two waypoints required");
            waypoints = List.copyOf(waypoints);
        }
    }
    public record Projection(String routeId, long routeVersion, long inputTs, double deviationDistance,
                             Geo.Point projectionPoint, int nearestSegmentIndex, double alongTrackDistance,
                             String crossTrackSide, double segmentLength) {}
    public record Progress(String routeId, long routeVersion, double traveledM, double fraction,
                           int nextWaypointIndex, boolean arrivalConfirmable) {}
    public record Landing(String landingType, String landingId, Geo.Point point, long decisionVersion, Integer waypointIndex) {}
    public record Remaining(boolean computable, Double distanceM, String reason, long routeVersion,
                            Long decisionVersion, String calculationQuality) {}

    /** Contract-facing entry point used by M4 and the detection owner. */
    public static Projection project(Route route, UavTelemetry telemetry) {
        if (telemetry == null || !Double.isFinite(telemetry.lon()) || !Double.isFinite(telemetry.lat()))
            throw new IllegalArgumentException("usable telemetry position required");
        return project(route, new Geo.Point(telemetry.lon(), telemetry.lat()), telemetry.ts());
    }

    public static Projection project(Route route, Geo.Point current, long inputTs) {
        double best = Double.POSITIVE_INFINITY, bestAlong = 0, bestLength = 0;
        Geo.Point bestPoint = null; int bestIndex = -1; String side = "ON_ROUTE";
        for (int i = 0; i < route.waypoints().size() - 1; i++) {
            Geo.Point a = route.waypoints().get(i), b = route.waypoints().get(i + 1);
            double length = Geo.distance(a, b), along, cross;
            if (length < 1e-6) { along = 0; cross = 0; }
            else {
                double arc = Geo.distance(a, current) / Geo.RADIUS_M;
                double headingDiff = Geo.bearing(a, current) - Geo.bearing(a, b);
                cross = Math.asin(Math.max(-1, Math.min(1, Math.sin(arc) * Math.sin(headingDiff)))) * Geo.RADIUS_M;
                along = Math.atan2(Math.sin(arc) * Math.cos(headingDiff), Math.cos(arc)) * Geo.RADIUS_M;
                along = Math.max(0, Math.min(length, along));
            }
            Geo.Point point = length < 1e-6 ? a : Geo.destination(a, Geo.bearing(a, b), along);
            double distance = Geo.distance(current, point);
            if (distance < best) { best = distance; bestPoint = point; bestIndex = i; bestAlong = along; bestLength = length;
                side = Math.abs(cross) < 1e-6 ? "ON_ROUTE" : cross > 0 ? "RIGHT" : "LEFT"; }
        }
        return new Projection(route.routeId(), route.version(), inputTs, best, bestPoint, bestIndex, bestAlong, side, bestLength);
    }

    public static Progress progress(Route route, Projection p, boolean imputed, boolean returningOrOffRoute) {
        checkRoute(route, p);
        double total = 0, traveled = p.alongTrackDistance();
        for (int i = 0; i < route.waypoints().size() - 1; i++) {
            double length = Geo.distance(route.waypoints().get(i), route.waypoints().get(i + 1));
            total += length;
            if (i < p.nearestSegmentIndex()) traveled += length;
        }
        return new Progress(route.routeId(), route.version(), traveled, total < 1e-6 ? 1 : Math.max(0, Math.min(1, traveled / total)),
                Math.min(route.waypoints().size() - 1, p.nearestSegmentIndex() + 1), !imputed && !returningOrOffRoute);
    }

    public static Remaining remaining(Route route, Projection p, Landing landing, boolean returningOrOffRoute, boolean imputed) {
        if (route == null || p == null) return new Remaining(false, null, "ROUTE_UNAVAILABLE", route == null ? -1 : route.version(), null, "not_computable");
        if (!route.routeId().equals(p.routeId()) || route.version() != p.routeVersion())
            return new Remaining(false, null, "ROUTE_VERSION_MISMATCH", route.version(), null, "not_computable");
        if (returningOrOffRoute) return new Remaining(false, null, "RETURN_OR_OFF_ROUTE_USE_E10_B16", route.version(), landing == null ? null : landing.decisionVersion(), "not_computable");
        if (landing == null || landing.point() == null || landing.landingType() == null || landing.landingType().isBlank() || landing.decisionVersion() < 0)
            return new Remaining(false, null, "E_LANDING_UNAVAILABLE", route.version(), null, "not_computable");
        int end = landing.waypointIndex() == null ? route.waypoints().size() - 1 : landing.waypointIndex();
        if (end < p.nearestSegmentIndex() + 1 || end >= route.waypoints().size())
            return new Remaining(false, null, "LANDING_ASSOCIATION_INVALID", route.version(), landing.decisionVersion(), "not_computable");
        double distance = p.segmentLength() - p.alongTrackDistance();
        for (int i = p.nearestSegmentIndex() + 1; i < end; i++) distance += Geo.distance(route.waypoints().get(i), route.waypoints().get(i + 1));
        if (landing.waypointIndex() == null || Geo.distance(route.waypoints().get(end), landing.point()) > 1)
            distance += Geo.distance(route.waypoints().get(end), landing.point());
        return new Remaining(true, Math.max(0, distance), null, route.version(), landing.decisionVersion(), imputed ? "imputed_geometry" : "observed_geometry");
    }
    private static void checkRoute(Route route, Projection p) {
        if (!route.routeId().equals(p.routeId()) || route.version() != p.routeVersion()) throw new IllegalArgumentException("Route version mismatch");
    }
}
