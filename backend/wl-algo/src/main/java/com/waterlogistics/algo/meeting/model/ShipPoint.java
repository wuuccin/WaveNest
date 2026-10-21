package com.waterlogistics.algo.meeting.model;

/**
 * 船舶预测轨迹点（M3 侧视图）——算法层 A 组完整快照 {@code points[i]} 的子集
 * （《29-各组算法层接口定义》§3.1.1、《80-A组算法方案》§2.3）。
 *
 * <p>投影纪律：只裁字段、不改语义（《22-接口契约》§2.1）。单位：位置为十进制度
 * （项目基准坐标系），不确定度为<b>标准差不是方差</b>，米（《22》§2.1.1）。
 *
 * @param t          该点时刻，Unix 毫秒（须为正）
 * @param lon        预测位置经度
 * @param lat        预测位置纬度
 * @param cog        该段航迹方位，度（由 M2 按相邻预测点派生，非 A 组原始 COG；接受 360° 并归一为 0°）
 * @param sigmaAlong 沿航迹方向不确定度（标准差），米（0 表示未知，由消费方兜底）
 * @param sigmaCross 法向不确定度（标准差），米（0 表示未知，由消费方兜底）
 */
public record ShipPoint(

        long t,

        double lon,

        double lat,

        double cog,

        double sigmaAlong,

        double sigmaCross) {

    public ShipPoint {
        if (t <= 0) {
            throw new IllegalArgumentException("预测点时刻必须为正，实际 " + t);
        }
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(
                    "预测点经纬度必须为有限数且在合法范围内，实际 lon=" + lon + ", lat=" + lat);
        }
        if (!Double.isFinite(cog) || cog < 0.0 || cog > 360.0) {
            throw new IllegalArgumentException("航迹方位必须为 [0, 360] 内的有限数，实际 " + cog);
        }
        if (cog == 360.0) {
            cog = 0.0;
        }
        if (!Double.isFinite(sigmaAlong) || sigmaAlong < 0.0
                || !Double.isFinite(sigmaCross) || sigmaCross < 0.0) {
            throw new IllegalArgumentException(
                    "沿航迹／法向标准差必须为有限非负值，实际 sigmaAlong=" + sigmaAlong
                            + ", sigmaCross=" + sigmaCross);
        }
    }
}
