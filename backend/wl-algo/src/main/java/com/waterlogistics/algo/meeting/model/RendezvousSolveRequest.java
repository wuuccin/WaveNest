package com.waterlogistics.algo.meeting.model;

import java.util.Objects;

/**
 * 会面点求解入参——组合级一次一调（《81-B组算法方案》§3.1、《32-M3-动态会面》§3.1）。
 *
 * <p>阈值与参数一律由调用方注入（来源 {@code sys_param}），本层不硬编码
 * （《81》§2.4 硬约束 2）；求解起点为当前时刻，无需「最早可起飞时刻」入参
 * （《32》§3.1 判定 1）。
 *
 * @param comboId             候选组合标识（会面点为组合级产物；重算路径回溯不到当前组合时可为 {@code null}）
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

    /** 不动点迭代次数的统一上界（三层共用，防配置异常导致不可控循环） */
    public static final int MAX_ITERATIONS = 1000;

    /** 会面时间窗余量的统一上界，秒（一天，防配置异常把窗口退化为截止时刻） */
    public static final int MAX_WINDOW_MARGIN_SEC = 86_400;

    public RendezvousSolveRequest {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        if (snapshot.points() == null || snapshot.points().isEmpty()) {
            throw new IllegalArgumentException("快照点列不能为空");
        }
        ShipPoint firstPoint = snapshot.points().get(0);
        ShipPoint lastPoint = snapshot.points().get(snapshot.points().size() - 1);
        if (nowMs < firstPoint.t() || nowMs > lastPoint.t()) {
            throw new IllegalArgumentException("求解起点须落在快照点列时间范围内，实际 nowMs=" + nowMs
                    + "，快照范围 [" + firstPoint.t() + ", " + lastPoint.t() + "]");
        }
        Objects.requireNonNull(representativeWind, "representativeWind 不能为空");
        if (maxIterations <= 0 || maxIterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "最大迭代次数须在 [1, " + MAX_ITERATIONS + "] 内，实际 " + maxIterations);
        }
        if (!Double.isFinite(convergeEpsilonSec) || convergeEpsilonSec <= 0) {
            throw new IllegalArgumentException("收敛阈值必须为正的有限数，实际 " + convergeEpsilonSec);
        }
        if (meetWindowMarginSec < 0 || meetWindowMarginSec > MAX_WINDOW_MARGIN_SEC) {
            throw new IllegalArgumentException(
                    "会面时间窗余量须在 [0, " + MAX_WINDOW_MARGIN_SEC + "] 秒内，实际 " + meetWindowMarginSec);
        }
        if (deadlineMs < nowMs) {
            throw new IllegalArgumentException(
                    "最晚会面时刻不得早于求解起点，实际 nowMs=" + nowMs + ", deadlineMs=" + deadlineMs);
        }
        if (!Double.isFinite(nestLon) || nestLon < -180.0 || nestLon > 180.0
                || !Double.isFinite(nestLat) || nestLat < -90.0 || nestLat > 90.0) {
            throw new IllegalArgumentException("机巢坐标必须为有限数且经纬度在合法范围内");
        }
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0) {
            throw new IllegalArgumentException("巡航速度必须为正的有限数，实际 " + cruiseSpeedMs);
        }
    }
}
