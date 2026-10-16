package com.waterlogistics.algo.estimation;

/**
 * 单个预测轨迹点及其不确定性（A 组 · estimation 子包）。
 *
 * <p>本类是算法层的自有输出对象，刻意不复用 {@code wl-contract} 的
 * {@code ShipTrajPoint}：算法层不感知 WS 载荷（《包结构设计》§二 铁律 4
 * 「输入输出对象自带」），由模块层负责映射成帧。
 *
 * <p><b>不确定性口径</b>：{@code sigmaAlongM}／{@code sigmaCrossM} 是
 * <b>标准差（1σ）</b>，不是方差；置信椭圆的半轴为 2σ
 * （契约 {@code ShipTrajPoint.semiMajorM() = 2 * sigmaAlong}）。
 *
 * @param tMs          预测点时刻，Unix 毫秒
 * @param lon          预测经度
 * @param lat          预测纬度
 * @param sigmaAlongM  沿航道方向 1σ，m
 * @param sigmaCrossM  横向 1σ，m
 */
public record EstimatedPoint(
        long tMs,
        double lon,
        double lat,
        double sigmaAlongM,
        double sigmaCrossM) {

    /** 置信椭圆半长轴（2σ），m。 */
    public double semiMajorM() {
        return 2.0 * sigmaAlongM;
    }

    /** 置信椭圆半短轴（2σ），m。 */
    public double semiMinorM() {
        return 2.0 * sigmaCrossM;
    }
}
