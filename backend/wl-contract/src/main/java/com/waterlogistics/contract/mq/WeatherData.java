package com.waterlogistics.contract.mq;

/**
 * 解析后的江面气象对象，对应主题 {@link TopicName#DATA_WEATHER}。
 *
 * <p>依据《22-接口契约》v1.2 §2 与 §7.2 U-05（2026-10-11 由 M1／C 组裁定）：
 *
 * <ul>
 *   <li>消费方式（首版）：一条即「某位置某时刻的风」，消费方按位置与时刻多次取用，不另立区域序列对象；
 *   <li>发布粒度：按位置与时刻发多条（不同站点／网格点、不同时刻各一条），
 *       <b>不得每周期只发一条全局代表值</b>，否则派单侧取「最不利风况」无数据可依；
 *   <li>时间语义：本对象同时给出观测时刻与目标时刻；{@code ts} 语义明确为观测时刻。
 * </ul>
 *
 * <p><b>首版不接入预报</b>：{@code targetTs} 的填充值＝观测时刻（实测代偿），
 * 且 {@code wind_safety} 已含预报误差；后续接入预报时只改填充值、不改结构。
 *
 * @param obsTs    观测时刻，Unix 毫秒
 * @param targetTs 目标时刻（预报目标时刻），Unix 毫秒；首版填值＝观测时刻
 * @param ts       观测时刻，Unix 毫秒（与 {@code obsTs} 同义，保留以兼容 §2 原字段）
 */
public record WeatherData(

        /** 站点／网格点经度 */
        double lon,

        /** 站点／网格点纬度 */
        double lat,

        /** 风速，米每秒 */
        double windSpeedMs,

        /** 风向，度 */
        double windDirDeg,

        /** 阵风风速，米每秒 */
        double gustMs,

        long obsTs,

        long targetTs,

        long ts) {
}
