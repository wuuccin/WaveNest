package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.ws.ShipTrajFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;

/**
 * 船舶预测快照——<b>算法层完整对象</b>，模块层对外交付的完整形态（M2 · 卢肄文）。
 *
 * <p><b>与 WS 帧的关系（重要）</b>：{@code ShipTrajFrame} 是本快照的<b>子集</b>，
 * 只面向大屏渲染；契约 javadoc 已声明帧缺 {@code zSeries}／{@code deviation}／
 * {@code enterTimes}／{@code branchId}／{@code calibrationFactor} 五项，
 * 并规定「E 组功能 17 的 NIS／σ 缩放判据须回算法层取完整对象，<b>不得从帧反推</b>」。
 * 本类即那个「完整对象」，也是 M3 经端口取数的载荷。
 *
 * <p><b>不确定性口径</b>：{@link ShipTrajPoint} 内的 σ 是<b>标准差</b>，椭圆半轴为 2σ
 * （契约 {@code semiMajorM() = 2 * sigmaAlong}），需要协方差时按
 * {@code Cov = R(ψ)·diag(σ∥², σ⊥²)·R(ψ)ᵀ}、{@code ψ = courseDeg} 重建。
 *
 * <p><b>★ 今天是简化版</b>：{@code zSeries}／{@code deviationM}／{@code enterTimesMs}／
 * {@code branchId}／{@code calibrationFactor} 五项一律为 {@code null} 或空集合——
 * 它们由精算版（滤波新息序列、预测—实测偏差、会面窗口、分支标识、标定因子）填充，
 * 属 {@code 80-A组算法方案} §3.2 范围，排期顺延。字段先占位，避免将来改签名。
 *
 * @param mmsi              船舶 MMSI
 * @param version           轨迹版本号，<b>每船单调递增</b>（判新旧只看本字段，不靠时间戳推测）
 * @param t0Ms              预测起始时刻，Unix 毫秒
 * @param courseDeg         主轴方位（椭圆旋转角），度
 * @param validitySec       有效期，s
 * @param premise           前提有效性，取 {@code PremiseStatus.code()}（VALID／INVALID／UNKNOWN）
 * @param points            预测点列，含 τ=0 的当前点
 * @param zSeries           新息序列（精算版，今天为 null）
 * @param deviationM        预测—实测偏差（精算版，今天为 null）
 * @param enterTimesMs      会面窗口进入时刻列表（精算版，今天为空）
 * @param branchId          分支标识（精算版，今天为 null）
 * @param calibrationFactor 标定因子（精算版，今天为 null）
 */
public record PredictionSnapshot(
        String mmsi,
        long version,
        long t0Ms,
        double courseDeg,
        long validitySec,
        String premise,
        List<ShipTrajPoint> points,
        List<Double> zSeries,
        Double deviationM,
        List<Long> enterTimesMs,
        String branchId,
        Double calibrationFactor) {

    public PredictionSnapshot {
        points = List.copyOf(points);
    }

    /**
     * 投影为对外 WS 帧 {@code ship_traj}——<b>只取子集</b>，不补任何默认值。
     *
     * <p>{@code premise} 已在本快照中编码为字符串，与帧的 {@code String premise} 直接对应。
     */
    public ShipTrajFrame toFrame() {
        return new ShipTrajFrame(version, t0Ms, courseDeg, validitySec, premise, points);
    }

    /** 依据有效期判断是否已过期（下游取数前应校验，过期须先刷新）。 */
    public boolean isStale(long nowMs) {
        return nowMs - t0Ms > validitySec * 1000L;
    }
}
