package com.waterlogistics.algo.meeting.model;

/**
 * 航点——{@code B_FlyableRoute.waypoints[i]} 与 {@code B_ReturnRoute.waypoints[i]} 共用
 * （《81-B组算法方案》§2.3 第（2）（5）项）。
 *
 * <p>单位：位置为十进制度（项目基准坐标系），高度与距离为米，速度为 m/s，
 * 时刻为 Unix 毫秒（《02-术语表》§5）。
 *
 * @param lon     航点经度
 * @param lat     航点纬度
 * @param altM    分段高度，米
 * @param speedMs 分段速度，m/s
 * @param t       到达该点时刻，Unix 毫秒
 */
public record Waypoint(

        double lon,

        double lat,

        double altM,

        double speedMs,

        long t) {

    public Waypoint {
        if (t < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException("航点时刻须为 Unix 毫秒值（下界 "
                    + EpochMillis.MIN_VALID_MS + "），实际 " + t);
        }
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("航点经度须为 [-180,180] 内的有限数，实际 " + lon);
        }
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("航点纬度须为 [-90,90] 内的有限数，实际 " + lat);
        }
        if (!Double.isFinite(altM) || altM < 0.0) {
            throw new IllegalArgumentException("航点高度须为非负有限数，实际 " + altM);
        }
        if (!Double.isFinite(speedMs) || speedMs < 0.0) {
            throw new IllegalArgumentException("航点速度须为非负有限数，实际 " + speedMs);
        }
        // 归一化：-0.0 → 0.0（保证 record 等值语义一致，同 Wind）
        lon += 0.0;
        lat += 0.0;
        altM += 0.0;
        speedMs += 0.0;
    }
}
