package com.waterlogistics.algo.meeting.model;

/**
 * 会面点求解入参——组合级一次一调（《81-B组算法方案》§3.1、《32-M3-动态会面》§3.1）。
 *
 * <p>阈值与参数一律由调用方注入（来源 {@code sys_param}），本层不硬编码
 * （《81》§2.4 硬约束 2）；求解起点为当前时刻，无需「最早可起飞时刻」入参
 * （《32》§3.1 判定 1）。
 *
 * @param comboId             候选组合标识（会面点为组合级产物）
 * @param nestLon             机巢起点经度
 * @param nestLat             机巢起点纬度
 * @param cruiseSpeedMs       机型巡航速度，m/s（机型档案）
 * @param representativeWind  代表风况（修正有效速度；首版每组合取一个，不逐段修正）
 * @param snapshot            船舶预测快照（携带版本号，一致性锚）
 * @param shipState           船当前状态锚点（可空）：6a 重算用状态船位定向有效速度，常规求解回落快照插值
 * @param nowMs               求解起点＝当前时刻，Unix 毫秒
 * @param deadlineMs          允许时限 T_max 对应的最晚会面时刻，Unix 毫秒（船驶出服务航段与订单时限取小，由上层算好）
 * @param meetWindowMarginSec 会面时间窗余量，秒（窗口＝[t*, t*＋余量]）
 * @param convergeEpsilonSec  不动点迭代收敛阈值 ε_t，秒
 * @param maxIterations       不动点迭代最大次数（不收敛转二分兜底）
 */
public record RendezvousSolveRequest(

        String comboId,

        double nestLon,

        double nestLat,

        double cruiseSpeedMs,

        Wind representativeWind,

        ShipTrackSnapshot snapshot,

        /**
         * 船当前状态锚点（可选）：6a 重算路径传状态船位用于定向有效速度，
         * 常规求解传 {@code null}、由快照插值回落（《81》§2.5、§3.1 步骤 2）。
         */
        ShipState shipState,

        long nowMs,

        long deadlineMs,

        int meetWindowMarginSec,

        double convergeEpsilonSec,

        int maxIterations) {
}
