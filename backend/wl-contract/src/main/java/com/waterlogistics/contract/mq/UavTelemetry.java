package com.waterlogistics.contract.mq;

/**
 * 解析后的无人机遥测对象，对应主题 {@link TopicName#DATA_UAV_TELEMETRY}。
 *
 * <p>依据《22-接口契约》v1.2 §2。电量一律百分比，满电＝100（《02-术语表》§5）。
 *
 * @param altM    海拔高度，米
 * @param speedMs 速度，米每秒
 * @param heading 航向，度
 * @param ts      观测时刻，Unix 毫秒
 */
public record UavTelemetry(

        /** 无人机编号 */
        String uavId,

        /** 经度，源原始坐标系 */
        double lon,

        /** 纬度，源原始坐标系 */
        double lat,

        double altM,

        double speedMs,

        /** 剩余电量，百分比（0–100） */
        double battery,

        double heading,

        long ts) {
}
