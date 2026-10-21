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
 * @param cogDeg 航向，度
 * @param sogMs  航速，m/s
 * @param t      状态时刻，Unix 毫秒（审计用：求解起点由重算请求的 nowMs 决定，本字段不参与求解）
 */
public record ShipState(

        double lon,

        double lat,

        double cogDeg,

        double sogMs,

        long t) {

    public ShipState {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("船位非法：lon=" + lon + ", lat=" + lat);
        }
        if (!Double.isFinite(cogDeg) || cogDeg < 0.0 || cogDeg >= 360.0) {
            throw new IllegalArgumentException("航向非法：cogDeg=" + cogDeg);
        }
        if (!Double.isFinite(sogMs) || sogMs < 0.0) {
            throw new IllegalArgumentException("航速非法：sogMs=" + sogMs);
        }
        if (t <= 0) {
            throw new IllegalArgumentException("状态时刻必须为正，实际 " + t);
        }
    }

    /**
     * 航向是否具方向语义（航速为 0 时无方向，下游不得据此定向）。
     *
     * @return {@code true}＝航速为正，{@code cogDeg} 可作方向使用
     */
    public boolean headingUsable() {
        return this.sogMs > 0.0;
    }
}
