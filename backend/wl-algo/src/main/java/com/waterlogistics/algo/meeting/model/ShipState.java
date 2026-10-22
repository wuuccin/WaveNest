package com.waterlogistics.algo.meeting.model;

/**
 * 船当前状态锚点——6a 会面点重算请求里 {@code stateSnapshot} 船侧字段的算法层视图
 * （《81-B组算法方案》§2.5：求解必需，确定「起点 → 当前预估会面点」的方向）。
 *
 * <p>注意：这<b>不是运动模型</b>——船的运动仍全部取自预测快照；求解只用
 * {@code lon}／{@code lat} 计算「巢 → 锚点」方位，{@code cogDeg}／{@code sogMs}
 * 随请求携带、仅供日志与审计，当前不参与求解。航速为 0 时航向不具方向语义，
 * 下游不得据此定向。
 *
 * @param lon    船位经度，十进制度（项目基准坐标系）
 * @param lat    船位纬度，十进制度（项目基准坐标系）
 * @param cogDeg 航向，度（航速为正时须为 [0,360) 的有限数；停船时可不可用）
 * @param sogMs  航速，m/s（0 至 {@link #MAX_SOG_MS}）
 * @param t      状态时刻，Unix 毫秒（6a 中与求解起点 nowMs 同值，两者须保持一致）
 */
public record ShipState(

        double lon,

        double lat,

        double cogDeg,

        double sogMs,

        long t) {

    /** 船速的统一上界，m/s（演示护栏；与请求侧共用，防量纲误传） */
    public static final double MAX_SOG_MS = 100.0;

    public ShipState {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("船位非法：lon=" + lon + ", lat=" + lat);
        }
        if (!Double.isFinite(sogMs) || sogMs < 0.0 || sogMs > MAX_SOG_MS) {
            throw new IllegalArgumentException("航速须在 [0, " + MAX_SOG_MS + "] m/s，实际 " + sogMs);
        }
        sogMs += 0.0;
        if (sogMs > 0.0) {
            if (!Double.isFinite(cogDeg) || cogDeg < 0.0 || cogDeg >= 360.0) {
                throw new IllegalArgumentException("航向非法：cogDeg=" + cogDeg);
            }
            cogDeg = ((cogDeg % 360.0) + 360.0) % 360.0 + 0.0;
        } else {
            // 停船：航向不可用时统一记为 NaN，避免 NaN／±Infinity 多种哨兵并存
            cogDeg = Double.isFinite(cogDeg)
                    ? ((cogDeg % 360.0) + 360.0) % 360.0 + 0.0
                    : Double.NaN;
        }
        if (t < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException("状态时刻须为 Unix 毫秒值（下界 "
                    + EpochMillis.MIN_VALID_MS + "），实际 " + t);
        }
    }

}
