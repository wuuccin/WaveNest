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
}
