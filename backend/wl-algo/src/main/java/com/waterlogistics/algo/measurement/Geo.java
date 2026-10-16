package com.waterlogistics.algo.measurement;

public final class Geo {
    public static final double RADIUS_M = 6371008.8;
    private Geo() {}
    public record Point(double lon, double lat) {
        public Point {
            if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90)
                throw new IllegalArgumentException("Invalid WGS-84 point");
        }
    }
    public static double distance(Point a, Point b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.lat())) *
                Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * RADIUS_M * Math.atan2(Math.sqrt(Math.min(1, h)), Math.sqrt(Math.max(0, 1 - h)));
    }
    public static double bearing(Point a, Point b) {
        double lat1 = Math.toRadians(a.lat()), lat2 = Math.toRadians(b.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        return Math.atan2(Math.sin(dLon) * Math.cos(lat2),
                Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon));
    }
    public static Point destination(Point start, double bearingRad, double meters) {
        double lat = Math.toRadians(start.lat()), lon = Math.toRadians(start.lon()), arc = meters / RADIUS_M;
        double endLat = Math.asin(Math.sin(lat) * Math.cos(arc) + Math.cos(lat) * Math.sin(arc) * Math.cos(bearingRad));
        double endLon = lon + Math.atan2(Math.sin(bearingRad) * Math.sin(arc) * Math.cos(lat), Math.cos(arc) - Math.sin(lat) * Math.sin(endLat));
        return new Point(Math.toDegrees(Math.atan2(Math.sin(endLon), Math.cos(endLon))), Math.toDegrees(endLat));
    }
    public static Point interpolate(Point a, Point b, double fraction) {
        if (fraction < 0 || fraction > 1 || !Double.isFinite(fraction)) throw new IllegalArgumentException("Invalid fraction");
        double arc = distance(a, b) / RADIUS_M;
        if (arc < 1e-12) return a;
        double sin = Math.sin(arc);
        if (Math.abs(sin) < 1e-10) throw new IllegalArgumentException("Antipodal interpolation is ambiguous");
        double wa = Math.sin((1 - fraction) * arc) / sin, wb = Math.sin(fraction * arc) / sin;
        double aLat = Math.toRadians(a.lat()), aLon = Math.toRadians(a.lon());
        double bLat = Math.toRadians(b.lat()), bLon = Math.toRadians(b.lon());
        double x = wa * Math.cos(aLat) * Math.cos(aLon) + wb * Math.cos(bLat) * Math.cos(bLon);
        double y = wa * Math.cos(aLat) * Math.sin(aLon) + wb * Math.cos(bLat) * Math.sin(bLon);
        double z = wa * Math.sin(aLat) + wb * Math.sin(bLat);
        return new Point(Math.toDegrees(Math.atan2(y, x)), Math.toDegrees(Math.atan2(z, Math.hypot(x, y))));
    }
}
