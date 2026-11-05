package com.waterlogistics.dispatch.prediction;

/**
 * 一条「预测 ↔ 实测」配对残差样本（11-02 阶段二 5 · 回流校准的输入单元）。
 *
 * <p>由吴可欣侧精算引擎在 {@code applyObservation} 处按实测点与预测点的差算出
 * （《11-02 任务清单》§二 顺序：吴在引擎算残差并暴露 {@code residualSamplesOf(mmsi)}，
 * 卢在服务层消费），本记录是跨人交界处的<b>数据形状约定</b>——两边按同一分量口径，
 * 校准器才有意义。
 *
 * <h3>分量口径（与 {@code 80} §5.1 的 NEES 公式严格对应，改动须两侧同时改）</h3>
 * <pre>
 *   ν = (errAlongM, errCrossM)          // 曲线坐标下的二维新息
 *   Cov = R(ψ)·diag(σ∥², σ⊥²)·R(ψ)ᵀ     // ψ = 参考线走向，本记录只带两个 σ
 *   NEES = (errAlongM/σ∥)² + (errCrossM/σ⊥)²
 * </pre>
 * <ul>
 *   <li>{@code errAlongM > 0}：实测点在预测位置<b>之前</b>（船比预测跑得快）；</li>
 *   <li>{@code errCrossM > 0}：实测点偏航道<b>右侧</b>（沿参考线前进方向的右手侧）；</li>
 *   <li>{@code errCourseDeg ∈ [-180, 180]}：取<b>最短弧</b>，与
 *       {@link PredictionService#shortestArcDeg} 同口径——直接相减会在 350°→10° 时得到 340° 的假跳变。</li>
 * </ul>
 *
 * <p><b>σ 必须一并带出</b>：NEES 的分母是<b>产生该预测那一版</b>的协方差。快照不落库
 * （见 {@link PredictionService} 类注释），那一版在新快照覆盖后即不可复现；若这里只给残差、
 * 事后回引擎索取 σ，拿到的会是「当前版」的 σ，与残差配错版本，NEES 就失去意义。
 *
 * @param mmsi        船舶 MMSI
 * @param predVersion 产生该预测的快照版本号（配对键的一半）
 * @param tauSec      预测时域 τ，s（实测时刻 − 预测起始时刻 t0）；可为 0，τ=0 的样本无信息量，
 *                    消费方会跳过校准器但仍落库留痕
 * @param errAlongM   纵向误差，m（带符号）
 * @param errCrossM   横向误差，m（带符号）
 * @param errCourseDeg 航向误差，度，最短弧；实测或预测无有效航向时为 {@code null}
 * @param sigmaAlongM 该预测点的纵向 σ∥，m（<b>标准差</b>，正数）
 * @param sigmaCrossM 该预测点的横向 σ⊥，m（<b>标准差</b>，正数）
 * @param vAlongMs    当前纵向速度估计，m/s（带符号；供偏差校准器按船速分桶）
 * @param sampleTsMs  样本时刻（实测点时刻），Unix 毫秒——消费方的去重水位与分桶基准
 */
public record ResidualSample(
        String mmsi,
        long predVersion,
        int tauSec,
        double errAlongM,
        double errCrossM,
        Double errCourseDeg,
        double sigmaAlongM,
        double sigmaCrossM,
        double vAlongMs,
        long sampleTsMs) {

    /**
     * 结构校验：只拒「不可能算出来」的值，不做业务合理性判断（那归校准器）。
     *
     * <p>为什么在构造器里拒：这些字段一旦为 NaN，{@code NeesCalibrator.nees} 与
     * {@code PredictionBiasCalibrator.Sample} 都会抛 {@link IllegalArgumentException}，
     * 而调用点在预测热路径上、异常没人接。在这里失败，故障点就在「谁造出了坏样本」。
     */
    public ResidualSample {
        if (mmsi == null || mmsi.isBlank()) {
            throw new IllegalArgumentException("mmsi 不能为空：样本无法归属到船");
        }
        if (!Double.isFinite(errAlongM) || !Double.isFinite(errCrossM)) {
            throw new IllegalArgumentException("残差必须为有限值：along=" + errAlongM
                    + " cross=" + errCrossM + "（mmsi=" + mmsi + "）");
        }
        if (errCourseDeg != null && !Double.isFinite(errCourseDeg)) {
            throw new IllegalArgumentException("航向误差非有限值：" + errCourseDeg
                    + "（应为 null 或有限角度，mmsi=" + mmsi + "）");
        }
        if (!(sigmaAlongM > 0.0) || !(sigmaCrossM > 0.0)) {
            throw new IllegalArgumentException("σ 必须为正有限值：σ∥=" + sigmaAlongM
                    + " σ⊥=" + sigmaCrossM + "（mmsi=" + mmsi + "）");
        }
    }
}
