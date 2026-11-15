package com.waterlogistics.dispatch.prediction;

/**
 * 6a 决策截止上报（《1112-功能做实批次｜接口约定》v1.2 §5）。
 *
 * <p>距会面时刻不大于 {@code rendezvousWindowMarginSec} 时，重算与下发一律停止，
 * 只把「已过决策截止」这一事实上报 M2，由 M2 侧留痕；不写 {@code sys_param}、
 * 不改订单状态、不推会面帧。
 *
 * @param orderId  订单号
 * @param mmsi     船舶标识
 * @param meetMs   会面时刻，Unix 毫秒
 * @param nowMs    判定时刻，Unix 毫秒
 * @param deltaDeg 当帧航向跳变最短弧差，度
 * @param reason   上报原因
 */
public record ReplanDecisionExpiredEvent(
        String orderId,
        String mmsi,
        long meetMs,
        long nowMs,
        double deltaDeg,
        String reason) {
}
