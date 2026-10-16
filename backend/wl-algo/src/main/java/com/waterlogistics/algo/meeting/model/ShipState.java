package com.waterlogistics.algo.meeting.model;

/**
 * 船当前状态锚点——6a 会面点重算请求里 {@code stateSnapshot} 船侧字段的算法层视图
 * （《81-B组算法方案》§2.5：求解必需，确定「起点 → 当前预估会面点」的方向）。
 *
 * <p>注意：这<b>不是运动模型</b>——船的运动仍全部取自预测快照；本锚点只用于定
 * 「朝哪飞」，供代表风况修正有效速度。
 *
 * @param lon    船位经度
 * @param lat    船位纬度
 * @param cogDeg 航向，度
 * @param sogMs  航速，m/s
 * @param t      状态时刻，Unix 毫秒（6a 以此为求解起点）
 */
public record ShipState(

        double lon,

        double lat,

        double cogDeg,

        double sogMs,

        long t) {
}
