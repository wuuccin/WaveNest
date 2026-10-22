package com.waterlogistics.rendezvous.handover;

/**
 * 末段判定参数——对应《81-B组算法方案》§3.7 参数名目（收敛阈值、相对位置误差阈值、
 * 相对速度阈值、稳定持续时间、交接风阈值）＋首版线性能耗率；全部由调用方注入
 * （来源 {@code sys_param}），本层不硬编码。
 *
 * @param approachRadiusM     接近段收敛阈值（相对距离低于此值进入匹配），米
 * @param posErrorM           相对位置误差阈值，米（须小于 {@code approachRadiusM}）
 * @param speedErrorMs        相对速度阈值，m/s
 * @param stableSec           稳定持续时间（位置与速度须持续达标；须为正），秒
 * @param windLimitMs         交接风阈值，m/s（须为正；缺失配置不得以 0 顶替）
 * @param energyRatePerSecPct 能耗率，%／s（首版线性能耗模型：能耗＝时长×率，结果封顶 100%；不能为负，可为 0）
 */
@SuppressWarnings("MissingJavadoc")
public record HandoverParams(

        double approachRadiusM,

        double posErrorM,

        double speedErrorMs,

        double stableSec,

        double windLimitMs,

        double energyRatePerSecPct) {

    public HandoverParams {
        if (!Double.isFinite(approachRadiusM) || !Double.isFinite(posErrorM) || !Double.isFinite(speedErrorMs)
                || !(approachRadiusM > (double) 0) || !(posErrorM > (double) 0) || !(speedErrorMs > (double) 0)) {
            throw new IllegalArgumentException(
                    "收敛与误差阈值必须为正的有限数，实际 approachRadiusM="
                            + approachRadiusM + ", posErrorM=" + posErrorM + ", speedErrorMs=" + speedErrorMs);
        }
        if (!(posErrorM < approachRadiusM)) {
            throw new IllegalArgumentException(
                    "相对位置误差阈值必须小于接近段收敛阈值，实际 posErrorM=" + posErrorM + ", approachRadiusM=" + approachRadiusM);
        }
        if (!Double.isFinite(stableSec) || !Double.isFinite(windLimitMs)
                || !Double.isFinite(energyRatePerSecPct)
                || !(stableSec > (double) 0) || !(windLimitMs > (double) 0) || !(energyRatePerSecPct >= (double) 0)) {
            throw new IllegalArgumentException(
                    "稳定持续时间须为正、风阈与能耗率不能为负且均须为有限数，实际 stableSec="
                            + stableSec + ", windLimitMs=" + windLimitMs + ", energyRatePerSecPct=" + energyRatePerSecPct);
        }
    }
}
