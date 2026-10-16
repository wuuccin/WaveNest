package com.waterlogistics.algo.estimation;

import java.util.List;

/**
 * 一条船的预测结果（A 组 · estimation 子包）。
 *
 * <p>算法层的自有输出对象（见 {@link EstimatedPoint} 的说明）。模块层把它映射为
 * {@code ShipTrajFrame}（帧只取子集）与完整 {@code PredictionSnapshot}（供 M3 端口取用）。
 *
 * @param mmsi        船舶 MMSI
 * @param t0Ms        预测起始时刻，Unix 毫秒（与 {@code ShipTrajFrame.t0} 同义）
 * @param courseDeg   主轴方位，度（椭圆旋转角，取自对地航向 COG）
 * @param validitySec 有效期，s
 * @param premise     前提有效性
 * @param points      预测点列，按时间递增，含 τ=0 的当前点
 */
public record PredictionResult(
        String mmsi,
        long t0Ms,
        double courseDeg,
        long validitySec,
        PremiseStatus premise,
        List<EstimatedPoint> points) {

    public PredictionResult {
        points = List.copyOf(points);
    }

    /** 预测最后一个点的时刻，Unix 毫秒；无点时回落到 {@link #t0Ms()}。 */
    public long lastTMs() {
        return points.isEmpty() ? t0Ms : points.get(points.size() - 1).tMs();
    }
}
