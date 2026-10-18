package com.waterlogistics.algo.meeting.model;

/**
 * 最近接近点——{@code B_ConflictResult.cpaPoint}（《81-B组算法方案》§2.3 第（4）项）。
 *
 * <p>单位：{@code lon}／{@code lat} 为十进制度（项目基准坐标系），{@code altM} 为米
 * （《02-术语表》§5）。
 *
 * @param lon  最近接近点经度（取双方位置中点），十进制度
 * @param lat  最近接近点纬度（取双方位置中点），十进制度
 * @param altM 最近接近点高度（取双方高度中点），米
 */
public record CpaPoint(

        double lon,

        double lat,

        double altM) {
}
