package com.waterlogistics.contract.ws;

import java.util.List;

/**
 * 船舶预测轨迹帧 {@code ship_traj}——依据《22-接口契约》v1.2 §4 与 §2.1.1（C-01）。
 *
 * <p>本帧是<b>模块层载荷</b>，为算法层完整 {@code PredictionSnapshot} 的<b>子集</b>：
 * 缺 {@code zSeries}／{@code deviation}／{@code enterTimes}／{@code branchId}／{@code calibrationFactor}。
 * E 组功能 17 的 NIS／σ 缩放判据须回算法层取完整对象，<b>不得从本帧反推</b>。
 *
 * <p>不确定性口径：对外交付的是<b>标准差标量对 ＋ 主轴方位</b>，不是经纬度 2×2 协方差矩阵。
 * 需矩阵时按下式重建：{@code Cov = R(ψ)·diag(sigmaAlong², sigmaCross²)·R(ψ)ᵀ}，{@code ψ = courseDeg}。
 *
 * @param mmsi        船舶标识——<b>HIGH.24 新增</b>。此前帧里没有任何船标识，大屏同时跟多船时
 *                    无法把一帧归到哪条船；推送信封也不带，故只能落在帧本身
 * @param version     轨迹版本号，判新旧用版本字段、不靠时间戳推测
 * @param t0          预测起始时刻，Unix 毫秒
 * @param courseDeg   主轴方位（旋转角），度
 * @param validitySec 预测有效期，秒
 * @param points      未来轨迹点列
 */
public record ShipTrajFrame(

        /** 船舶标识（MMSI），帧归属键 */
        String mmsi,

        long version,

        long t0,

        double courseDeg,

        long validitySec,

        /** 前提有效性，暂按字符串透传；取值口径待 A 组补齐后回填 */
        String premise,

        List<ShipTrajPoint> points) {

    /**
     * 结构校验（评审 MEDIUM.11 / LOW.21 同源）：只拒「下游必然炸」的形态。
     *
     * <p>为什么在契约里拒而不是在各生产方里拒：本帧是三个消费方的共同输入
     * （推送层、看台接口、前端），任何一方拿到 {@code points == null} 都会在自己的
     * 序列化处抛 NPE，堆栈落在无关的模块；在构造期失败，故障点才指向「谁造出了坏帧」。
     */
    public ShipTrajFrame {
        if (mmsi == null || mmsi.isBlank()) {
            throw new IllegalArgumentException("mmsi 不能为空：ship_traj 帧须可归属到船");
        }
        if (points == null) {
            throw new IllegalArgumentException("points 不得为 null：无预测应不发帧，而不是发空帧");
        }
        points = List.copyOf(points);
    }
}
