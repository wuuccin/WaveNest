package com.waterlogistics.algo.meeting.model;

/**
 * 最近接近点——{@code B_ConflictResult.cpaPoint}（《81-B组算法方案》§2.3 第（4）项）。
 *
 * @param lon  最近接近点经度（取双方位置中点）
 * @param lat  最近接近点纬度（取双方位置中点）
 * @param altM 最近接近点高度（取双方高度中点），米
 */
public record CpaPoint(

        double lon,

        double lat,

        double altM) {
}
