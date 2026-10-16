package com.waterlogistics.algo.meeting;

/**
 * 经纬度局部米制近似——会面求解与航迹推演的几何支撑（《81-B组算法方案》§3.1）。
 *
 * <p>作业区尺度（十公里级）下用等距圆球近似即可：度纬恒长、度经随纬度收缩；
 * 不引入完整大地测量（演示口径，精算版另立）。
 */
public final class GeoApprox {

    /** 地球平均半径，米 */
    private static final double EARTH_RADIUS_M = 6371000.0;

    /** 每度纬差对应的米数（π·R/180） */
    private static final double METERS_PER_DEG = Math.PI * EARTH_RADIUS_M / 180.0;

    private GeoApprox() {
    }

    /**
     * 局部平面近似距离（等距圆球），米；两点间距远小于地球半径时误差可忽略。
     */
    public static double distanceM(double lon1, double lat1, double lon2, double lat2) {
        double midLatRad = Math.toRadians((lat1 + lat2) / 2);
        double dx = (lon2 - lon1) * METERS_PER_DEG * Math.cos(midLatRad);
        double dy = (lat2 - lat1) * METERS_PER_DEG;
        return Math.hypot(dx, dy);
    }

    /**
     * 起点指向终点的初始方位角，度（0° 正北、顺时针）。
     */
    public static double bearingDeg(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }
}
