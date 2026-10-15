package com.waterlogistics.contract.mq;

/**
 * 解析后的 AIS 船位对象，对应主题 {@link TopicName#DATA_AIS}。
 *
 * <p>依据《22-接口契约》v1.2 §2。字段保持源原始单位与坐标系，
 * 归一由 M6 在落库／入缓存时完成（《02-术语表》§5）。
 *
 * @param ts 观测时刻，Unix 毫秒
 */
public record AisMessage(

        /** 船舶 MMSI */
        String mmsi,

        /** 船名 */
        String shipName,

        /** 经度，源原始坐标系 */
        double lon,

        /** 纬度，源原始坐标系 */
        double lat,

        /** 对地航速 SOG，节 */
        double sog,

        /** 对地航向 COG，度 */
        double cog,

        long ts) {
}
