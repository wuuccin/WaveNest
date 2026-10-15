package com.waterlogistics.contract.ws;

/**
 * 船舶未来轨迹点，含沿航迹／法向标准差——依据《22-接口契约》v1.2 §2.1.1（C-01）。
 *
 * <p><b>这里给的是标准差不是方差</b>（单位：米），差一个平方量级，前端椭圆才能画对：
 * 椭圆半轴 {@code a = 2·sigmaAlong}、{@code b = 2·sigmaCross}，旋转角取帧级 {@code courseDeg}。
 *
 * @param lon         预测位置经度
 * @param lat         预测位置纬度
 * @param sigmaAlong  沿航迹方向不确定度（标准差），米
 * @param sigmaCross  法向不确定度（标准差），米
 * @param t           该点的预测时刻，Unix 毫秒
 */
public record ShipTrajPoint(

        long t,

        double lon,

        double lat,

        double sigmaAlong,

        double sigmaCross) {

    /** 置信椭圆长半轴：a = 2·sigmaAlong */
    public double semiMajorM() {
        return 2 * sigmaAlong;
    }

    /** 置信椭圆短半轴：b = 2·sigmaCross */
    public double semiMinorM() {
        return 2 * sigmaCross;
    }
}
