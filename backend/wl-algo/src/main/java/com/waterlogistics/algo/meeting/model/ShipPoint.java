package com.waterlogistics.algo.meeting.model;

/**
 * 船舶预测轨迹点（M3 侧视图）——算法层 A 组完整快照 {@code points[i]} 的子集
 * （《29-各组算法层接口定义》§3.1.1、《80-A组算法方案》§2.3）。
 *
 * <p>投影纪律：只裁字段、不改语义（《22-接口契约》§2.1）。单位：位置为十进制度
 * （项目基准坐标系），不确定度为<b>标准差不是方差</b>，米（《22》§2.1.1）。
 *
 * @param t          该点时刻，Unix 毫秒
 * @param lon        预测位置经度
 * @param lat        预测位置纬度
 * @param cog        该段航迹方位，度
 * @param sigmaAlong 沿航迹方向不确定度（标准差），米
 * @param sigmaCross 法向不确定度（标准差），米
 */
public record ShipPoint(

        long t,

        double lon,

        double lat,

        double cog,

        double sigmaAlong,

        double sigmaCross) {
}
