package com.waterlogistics.contract.mq;

/**
 * 解析后的水文对象，对应主题 {@link TopicName#DATA_HYDRO}。
 *
 * <p>依据《22-接口契约》v1.2 §2。
 *
 * @param waterLevelM 水位，米
 * @param flowMs      流速，米每秒
 * @param ts          观测时刻，Unix 毫秒
 */
public record HydroData(

        /** 测点经度 */
        double lon,

        /** 测点纬度 */
        double lat,

        double waterLevelM,

        double flowMs,

        long ts) {
}
