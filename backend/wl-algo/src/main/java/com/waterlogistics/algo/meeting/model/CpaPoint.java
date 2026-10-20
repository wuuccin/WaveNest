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

    public CpaPoint {
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(
                    "最近接近点经纬度必须为有限数且在合法范围内，实际 lon=" + lon + ", lat=" + lat);
        }
        if (!Double.isFinite(altM)) {
            throw new IllegalArgumentException("最近接近点高度必须为有限数，实际 altM=" + altM);
        }
    }
}
