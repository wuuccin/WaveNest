package com.waterlogistics.contract.mq;

/**
 * 可飞航迹的一个航点——照抄《81-B组算法方案》§2.3 第 2 项
 * {@code B_FlyableRoute.waypoints[i]} 子表，字段／类型／单位一字不改。
 *
 * <p>本对象是算法层 {@code waypoints[i]} 在模块层的<b>原样投影</b>：
 * 《22-接口契约》§2.1 投影表规定 {@code task.plan} 内嵌无人机航迹
 * 「只投影 {@code waypoints} ＋ {@code routeVersion}」，
 * 而 waypoints 内部五个字段<b>不再裁剪</b>（裁剪发生在对象级，不在航点级）。
 *
 * @param lon     航点经度，十进制度（基准坐标系）
 * @param lat     航点纬度，十进制度（基准坐标系）
 * @param altM    分段高度，米
 * @param speedMs 分段速度，米每秒
 * @param t       到达该点时刻，Unix 毫秒（航点序列时间升序）
 */
public record RouteWaypoint(double lon, double lat, double altM, double speedMs, long t) {
}
