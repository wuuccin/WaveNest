package com.waterlogistics.algo.meeting;

import com.waterlogistics.algo.meeting.model.Wind;


/**
 * 有效速度修正——代表风况下沿「巢 → 会面点」方向的地速（《81-B组算法方案》§3.1 步骤 2）。
 *
 * <p>口径：空速（机型巡航速度）沿航向分量 ＋ 风沿航向分量；风向取「来向」约定
 * （见 {@link Wind}）。纯逆风减速、纯顺风加速，公式为
 * {@code v = cruise − windSpeed·cos(windDir − bearing)}。
 *
 * <p>返回值可能 ≤ 0（强逆风），由调用方按「追不上」处理——本类不截断、不臆造下限，
 * 保持纯函数。
 */
public final class EffectiveSpeed {

    private EffectiveSpeed() {
    }

    /**
     * 沿航向的地速。
     *
     * @param cruiseSpeedMs 机型巡航速度，m/s
     * @param wind          代表风况
     * @param bearingDeg    飞行方位（巢 → 预估会面点），度
     * @return 有效速度，m/s（可为负）
     * @throws IllegalArgumentException 风况为空、巡航速度非正/非有限，或方位非有限
     */
    public static double alongBearing(double cruiseSpeedMs, Wind wind, double bearingDeg) {
        if (wind == null) {
            throw new IllegalArgumentException("wind 不能为空");
        }
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0 || !Double.isFinite(bearingDeg)
                || bearingDeg < -360.0 || bearingDeg > 720.0) {
            throw new IllegalArgumentException("有效速度入参非法（巡航速度须为正、方位须在 [-360, 720] 度内），实际 cruise="
                    + cruiseSpeedMs + ", bearing=" + bearingDeg);
        }
        // 方位归一化到 [0,360)，避免大数值方位在角度差中丢失精度
        double bearing = ((bearingDeg % 360.0) + 360.0) % 360.0;
        double relRad = Math.toRadians(wind.dirDeg() - bearing);
        return cruiseSpeedMs - wind.speedMs() * Math.cos(relRad);
    }
}
