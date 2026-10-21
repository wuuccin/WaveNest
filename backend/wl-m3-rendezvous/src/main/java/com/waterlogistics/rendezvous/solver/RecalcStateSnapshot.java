package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.EpochMillis;
import com.waterlogistics.algo.meeting.model.ShipState;



/**
 * 6a 重算请求内嵌的当前状态快照——字段拆到字段级（《81-B组算法方案》§2.5 表）。
 *
 * <p>船侧五字段<b>求解必需</b>（确定方向与求解起点）；机侧字段<b>非必需</b>——6a 求解
 * 起点仍是机巢，机位／电量仅追溯用，不参与求解。<b>机侧四字段须同时缺省或同时提供</b>
 * （任一缺失即拒绝，避免部分追溯数据进入记录）。机侧时刻与船状态时刻为<b>独立采样</b>，
 * 不校验两者差值。
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
@SuppressWarnings("MissingJavadoc")
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

    public RecalcStateSnapshot {
        if (!Double.isFinite(shipLon) || !Double.isFinite(shipLat)
                || shipLon < -180.0 || shipLon > 180.0 || shipLat < -90.0 || shipLat > 90.0) {
            throw new IllegalArgumentException("船位经纬度必须为合法范围内的有限数");
        }
        if (!Double.isFinite(sogMs) || sogMs < 0.0 || sogMs > ShipState.MAX_SOG_MS) {
            throw new IllegalArgumentException("船速须在 [0, " + ShipState.MAX_SOG_MS + "] m/s，实际 " + sogMs);
        }
        if (sogMs > 0.0) {
            if (!Double.isFinite(shipCogDeg) || shipCogDeg < 0.0 || shipCogDeg >= 360.0) {
                throw new IllegalArgumentException("船航向非法：shipCogDeg=" + shipCogDeg);
            }
        } else {
            shipCogDeg = Double.isFinite(shipCogDeg)
                    ? ((shipCogDeg % 360.0) + 360.0) % 360.0
                    : Double.NaN;
        }
        if (shipStateT < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException("船状态时刻须为 Unix 毫秒值（下界 "
                    + EpochMillis.MIN_VALID_MS + "），实际 " + shipStateT);
        }
        boolean droneAllNull = droneLon == null && droneLat == null && droneBattery == null && droneStateT == null;
        boolean droneAllSet = droneLon != null && droneLat != null && droneBattery != null && droneStateT != null;
        if (!droneAllNull && !droneAllSet) {
            throw new IllegalArgumentException("机侧四个字段须同时缺省或同时提供");
        }
        if (droneAllSet) {
            if (!Double.isFinite(droneLon.doubleValue()) ||
                    !Double.isFinite(droneLat.doubleValue()) ||
                    !Double.isFinite(droneBattery.doubleValue()) ||
                    droneLon.doubleValue() < -180.0 ||
                    droneLon.doubleValue() > 180.0 ||
                    droneLat.doubleValue() < -90.0 ||
                    droneLat.doubleValue() > 90.0 ||
                    droneBattery.doubleValue() < 0.0 ||
                    droneBattery.doubleValue() > 100.0) {
                throw new IllegalArgumentException("机侧状态字段非法：经纬度或电量越界，或不是有限数");
            }
            if (droneStateT.longValue() < EpochMillis.MIN_VALID_MS) {
                throw new IllegalArgumentException("机状态时刻须为 Unix 毫秒值（下界 "
                        + EpochMillis.MIN_VALID_MS + "），实际 " + droneStateT);
            }
        }
    }
}
