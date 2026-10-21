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

    /** 每度纬差对应的米数（π·R/180），供全工程复用，避免另立第二套米制定义 */
    public static final double METERS_PER_DEG = Math.PI * EARTH_RADIUS_M / 180.0;

    private GeoApprox() {
    }

    /**
     * 局部平面近似距离（等距圆球），米；两点间距远小于地球半径时误差可忽略。
     *
     * <p>经差先归一化到 [−180, 180]（跨 ±180° 经线时不取整球跨度）。本类距离用
     * 等距近似、方位用球面初始方位，两种模型只在十公里级作业区一致；纬向跨度越大
     * 误差越大，超出十公里级请改用球面距离，勿超尺度复用。
     */
    public static double distanceM(double lon1, double lat1, double lon2, double lat2) {
        requireLonLat(lon1, lat1);
        requireLonLat(lon2, lat2);
        double midLatRad = Math.toRadians((lat1 + lat2) / 2);
        double dLon = normalizeLonDelta(lon1, lon2);
        double dx = dLon * METERS_PER_DEG * Math.cos(midLatRad);
        double dy = (lat2 - lat1) * METERS_PER_DEG;
        return Math.hypot(dx, dy);
    }

    /**
     * 起点指向终点的初始方位角，度（0° 正北、顺时针）。
     *
     * <p>两点重合时方位无定义（{@code atan2(0,0)=0}），此处返回正北；调用方不得把零长航段
     * 用于风向修正。
     */
    public static double bearingDeg(double lon1, double lat1, double lon2, double lat2) {
        requireLonLat(lon1, lat1);
        requireLonLat(lon2, lat2);
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(normalizeLonDelta(lon1, lon2));
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /**
     * 经差归一化到 [-180, 180]；恰为 -180 时折到 +180，消除对跖点由浮点抖动引起的
     * 180° 翻转。
     */
    private static double normalizeLonDelta(double lon1, double lon2) {
        double dLon = lon2 - lon1;
        if (dLon > 180.0) {
            dLon -= 360.0;
        } else if (dLon <= -180.0) {
            dLon += 360.0;
        }
        return dLon;
    }

    private static void requireLonLat(double lon, double lat) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(
                    "经纬度必须为有限数且在合法范围内，实际 lon=" + lon + ", lat=" + lat);
        }
    }
}
