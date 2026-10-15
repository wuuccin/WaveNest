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
 * @param version     轨迹版本号，判新旧用版本字段、不靠时间戳推测
 * @param t0          预测起始时刻，Unix 毫秒
 * @param courseDeg   主轴方位（旋转角），度
 * @param validitySec 预测有效期，秒
 * @param points      未来轨迹点列
 */
public record ShipTrajFrame(

        long version,

        long t0,

        double courseDeg,

        long validitySec,

        /** 前提有效性，暂按字符串透传；取值口径待 A 组补齐后回填 */
        String premise,

        List<ShipTrajPoint> points) {
}
