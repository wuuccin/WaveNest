package com.waterlogistics.rendezvous.solver;

/**
 * 6a 重算请求内嵌的当前状态快照——字段拆到字段级（《81-B组算法方案》§2.5 表）。
 *
 * <p>船侧五字段<b>求解必需</b>（确定方向与求解起点）；机侧字段<b>非必需</b>——6a 求解
 * 起点仍是机巢，机位／电量仅追溯用，不参与求解。
 *
 * @param shipLon      船位经度（求解必需）
 * @param shipLat      船位纬度（求解必需）
 * @param shipCogDeg   船航向，度（求解必需）
 * @param sogMs        船航速，m/s（求解必需）
 * @param shipStateT   船状态时刻，Unix 毫秒（求解必需；6a 以此为求解起点）
 * @param droneLon     机位经度（非必需，追溯用）
 * @param droneLat     机位纬度（非必需，追溯用）
 * @param droneBattery 电量，满电百分比（非必需，追溯用）
 * @param droneStateT  机状态时刻，Unix 毫秒（非必需，追溯用）
 */
public record RecalcStateSnapshot(

        double shipLon,

        double shipLat,

        double shipCogDeg,

        double sogMs,

        long shipStateT,

        Double droneLon,

        Double droneLat,

        Double droneBattery,

        Long droneStateT) {
}
