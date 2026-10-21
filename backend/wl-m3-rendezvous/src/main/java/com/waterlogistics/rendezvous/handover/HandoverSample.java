package com.waterlogistics.rendezvous.handover;

/**
 * 末段采样——判据推进的输入单元（《81-B组算法方案》§3.6）。
 *
 * <p>甲板参考点与姿态首版不入参（本组自定项 B-11）——只用<b>船位</b>定义参考点；
 * 「投放机构反馈完成」首版简化为 {@code dropDone} 标记（演示由样本序列给出）。
 * 时间须<b>严格升序</b>（单样本无法自校验，由 {@code HandoverCommand} 构造期拒绝非增时间），
 * 相对速度由相邻样本差分得出，故相邻样本 dt 须为严格正值。
 *
 * @param t           采样时刻，Unix 毫秒（须为正）
 * @param droneLon    机位经度
 * @param droneLat    机位纬度
 * @param shipLon     船位经度
 * @param shipLat     船位纬度
 * @param windSpeedMs 当时风况（风速），m/s——交接时刻须满足交接风阈值
 * @param dropDone    投放机构反馈完成标记（首版简化）
 */
@SuppressWarnings("MissingJavadoc")
public record HandoverSample(

        long t,

        double droneLon,

        double droneLat,

        double shipLon,

        double shipLat,

        double windSpeedMs,

        boolean dropDone) {

    public HandoverSample {
        if (t <= 0L) {
            throw new IllegalArgumentException("采样时刻必须为正的 Unix 毫秒值，实际 " + t);
        }
        if (!Double.isFinite(droneLon) || !Double.isFinite(droneLat)
                || !Double.isFinite(shipLon) || !Double.isFinite(shipLat)
                || droneLon < -180.0 || droneLon > 180.0 || droneLat < -90.0 || droneLat > 90.0
                || shipLon < -180.0 || shipLon > 180.0 || shipLat < -90.0 || shipLat > 90.0) {
            throw new IllegalArgumentException("采样坐标必须为有限数且经纬度在合法范围内，实际 droneLon="
                    + droneLon + ", droneLat=" + droneLat + ", shipLon=" + shipLon + ", shipLat=" + shipLat);
        }
        if (!Double.isFinite(windSpeedMs) || windSpeedMs < 0.0) {
            throw new IllegalArgumentException("风速不能为负或非有限数，实际 " + windSpeedMs);
        }
    }
}
