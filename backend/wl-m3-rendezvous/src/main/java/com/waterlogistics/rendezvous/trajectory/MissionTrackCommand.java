package com.waterlogistics.rendezvous.trajectory;

import java.util.Objects;

/**
 * 三段任务航迹生成入参——供大屏「无人机三段航迹」分段着色的演示数据
 * （《43-编码分工说明》§6.3：去程／末段交接／返程，产出分段着色数据）。
 *
 * <p>三段几何：去程「机巢 → 会面点」、末段交接「会面点 → 交接完成位置」、
 * 返程「交接完成位置 → 归巢落点」；三段分别走同一项航迹生成能力后拼接打标。
 * 时间自 {@code takeoffMs} 起按各段精算时长顺延。
 *
 * @param comboId          候选组合标识
 * @param routeId          任务航迹标识（三段航迹以其加后缀命名）
 * @param nestLon          机巢经度
 * @param nestLat          机巢纬度
 * @param meetLon          会面点经度
 * @param meetLat          会面点纬度
 * @param handoverEndLon   交接完成位置经度（末段终点）
 * @param handoverEndLat   交接完成位置纬度
 * @param returnTargetLon  归巢落点经度
 * @param returnTargetLat  归巢落点纬度
 * @param cruiseAltM       巡航高度，米
 * @param outboundSpeedMs  去程速度，m/s
 * @param terminalSpeedMs  末段速度，m/s（伴飞随船，通常显著低于巡航）
 * @param returnSpeedMs    返程速度，m/s
 * @param takeoffMs        起飞时刻（第一段起点时刻），Unix 毫秒
 * @param waypointsPerLeg  每段航点数（≥2）
 */
public record MissionTrackCommand(

        String comboId,

        String routeId,

        double nestLon,

        double nestLat,

        double meetLon,

        double meetLat,

        double handoverEndLon,

        double handoverEndLat,

        double returnTargetLon,

        double returnTargetLat,

        double cruiseAltM,

        double outboundSpeedMs,

        double terminalSpeedMs,

        double returnSpeedMs,

        long takeoffMs,

        int waypointsPerLeg) {

    public MissionTrackCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        Objects.requireNonNull(routeId, "routeId 不能为空");
    }
}
