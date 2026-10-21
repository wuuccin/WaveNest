package com.waterlogistics.rendezvous.port;

import java.util.Objects;



/**
 * 执行机台账反查结果——6a 重算按 {@code droneId} 反查所需的最小上下文
 * （《81-B组算法方案》§2.5：定位机型、归属机巢；返回可带 comboId，由该机当前任务回溯）。
 *
 * <p>归属口径：<b>飞行中归属不变</b>——迫停只改 {@code currentNestId}，{@code homeNestId}
 * 不动（《23-状态机定义》§7 约定 3）。
 *
 * @param droneId       执行机实例
 * @param homeNestId    归属机巢标识
 * @param nestLon       归属机巢经度（6a 求解起点仍是机巢）
 * @param nestLat       归属机巢纬度
 * @param cruiseSpeedMs 机型巡航速度，m/s（须为正的有限数）
 * @param comboId       当前任务回溯的组合标识；取不到为 {@code null}（透传进返回结果）
 */
@SuppressWarnings("MissingJavadoc")
public record DroneContext(

        String droneId,

        String homeNestId,

        double nestLon,

        double nestLat,

        double cruiseSpeedMs,

        String comboId) {






    public DroneContext {
        Objects.requireNonNull(droneId, "droneId 不能为空");
        Objects.requireNonNull(homeNestId, "homeNestId 不能为空");
        droneId = droneId.strip();
        homeNestId = homeNestId.strip();
        if (droneId.isEmpty() || homeNestId.isEmpty()) {
            throw new IllegalArgumentException("droneId/homeNestId 不能为空白字符串");
        }
        if (comboId != null) {
            comboId = comboId.strip();
            if (comboId.isEmpty()) {
                comboId = null;
            }
        }
        if (!Double.isFinite(nestLon) || !Double.isFinite(nestLat)
                || nestLon < -180.0 || nestLon > 180.0 || nestLat < -90.0 || nestLat > 90.0) {
            throw new IllegalArgumentException("机巢坐标必须为有限数且经纬度在合法范围内");
        }
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0.0) {
            throw new IllegalArgumentException(
                    "机型巡航速度必须为正的有限数，实际 " + cruiseSpeedMs);
        }
    }
}
