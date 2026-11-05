package com.waterlogistics.dispatch.taskstate;

import java.util.Optional;

/**
 * 无人机实时位置出口（M2 · 任务 17 位置驱动末段切入）。
 *
 * <p><b>为什么是端口而不是直接查 M4</b>：M2 不依赖 M4 的存储实现；位置来源由装配层注入，
 * 便于单测与后续替换。当前实现见 {@code wl-app} 的
 * {@code FlightRecordStoreDronePositionAdapter}（复用 M4 已接入的飞行记录，不新建 MQ 订阅，
 * 遵守 {@code TopicName.DATA_UAV_TELEMETRY} 的「遥测只接入一次」约定）。
 *
 * <p><b>失败口径</b>：遥测未就绪、记录不足或几何不可用时返回 {@link Optional#empty()}，
 * 调用方<b>必须</b>回落到 {@code meet_time} 时间轴，不得因缺位置卡死飞行阶段推进。
 */
@FunctionalInterface
public interface DronePositionPort {

    /**
     * 该机最新一个可用于几何判定的位置。
     *
     * @param droneId 无人机编号
     * @return 最新位置；不可用时为空
     */
    Optional<Position> latest(String droneId);

    /** 经纬度（WGS-84）。 */
    record Position(double lon, double lat) {
    }
}
