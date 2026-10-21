package com.waterlogistics.algo.meeting.model;

/**
 * 最近接近点——{@code B_ConflictResult.cpaPoint}（《81-B组算法方案》§2.3 第（4）项）。
 *
 * <p>单位：{@code lon}／{@code lat} 为十进制度（项目基准坐标系），{@code altM} 为米
 * （《02-术语表》§5）。
 *
 * <p>经度统一规范化到 [-180, 180)，{@code 180} 视为 {@code -180}；高度基准为海平面以上，
 * 故 {@code altM} 非负。各分量归一并去除 {@code -0.0} 符号位，保证等值对象等值。
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
        if (!Double.isFinite(altM) || altM < 0.0) {
            throw new IllegalArgumentException("最近接近点高度必须为非负有限数，实际 altM=" + altM);
        }
        if (lon == 180.0) {
            lon = -180.0;
        }
        // 归一 -0.0 → +0.0，保证等值 CPA 点的 equals/hashCode/toString 一致
        lon = (lon == 0.0) ? 0.0 : lon;
        lat = (lat == 0.0) ? 0.0 : lat;
        altM = (altM == 0.0) ? 0.0 : altM;
    }
}
