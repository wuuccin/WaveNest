package com.waterlogistics.rendezvous.handover;

/**
 * 末段判定参数——对应《81-B组算法方案》§3.7 参数名目（收敛阈值、相对位置误差阈值、
 * 相对速度阈值、稳定持续时间、交接风阈值）＋首版线性能耗率；全部由调用方注入
 * （来源 {@code sys_param}），本层不硬编码。
 *
 * @param approachRadiusM     接近段收敛阈值（相对距离低于此值进入匹配），米
 * @param posErrorM           相对位置误差阈值，米
 * @param speedErrorMs        相对速度阈值，m/s
 * @param stableSec           稳定持续时间（位置与速度须持续达标），秒
 * @param windLimitMs         交接风阈值，m/s
 * @param energyRatePerSecPct 能耗率，%／s（首版线性能耗模型：能耗＝时长×率）
 */
public record HandoverParams(

        double approachRadiusM,

        double posErrorM,

        double speedErrorMs,

        double stableSec,

        double windLimitMs,

        double energyRatePerSecPct) {
}
