package com.waterlogistics.algo.candidate;

import java.util.List;

/**
 * 候选生成输入（纯 Java 对象，无 Spring/DB——数据由调用方注入）。
 *
 * <p>字段与 37 的 drone/nest 表对齐（简化演示版只取用到的子集）。
 */
public record CandidateInput(
        /** 订单取货点经度 */
        double pickupLon,
        /** 订单取货点纬度 */
        double pickupLat,
        /** 载荷重量（kg） */
        double payloadKg,
        /** 机巢列表 */
        List<NestView> nests,
        /** 无人机列表 */
        List<DroneView> drones) {

    /** 机巢视图 */
    public record NestView(
            String nestId,
            double lon,
            double lat,
            /** 可驻留机型（机型名列表） */
            List<String> allowedTypes) {
    }

    /** 无人机视图 */
    public record DroneView(
            String droneId,
            String nestId,
            String type,
            /** 状态：空闲/占用/故障/充电中（照 23 §7 既定状态词） */
            String status,
            /** 剩余电量（%） */
            double battery,
            /** 最大载荷（kg） */
            double maxPayloadKg,
            /**
             * 机型抗风上限（m/s）——机型能力包线「抗风等级」。
             *
             * <p>取 {@code drone.wind_limit_ms}；<b>未登记时为 {@code NaN}</b>（不写成 0，
             * 也不写成某个"看起来合理"的默认值——0 会被误判成"完全不能吹风"，
             * 默认值会造出假的机型差异）。判定见 {@code FeasibilityJudge}。
             */
            double windLimitMs,
            /** 机型巡航速度（m/s）——机型能力包线「巡航速度」；未登记时为 {@code NaN} */
            double cruiseSpeedMs) {

        /** 抗风上限是否已登记（可判定） */
        public boolean hasWindLimit() {
            return !Double.isNaN(windLimitMs) && Double.isFinite(windLimitMs) && windLimitMs > 0;
        }

        /** 巡航速度是否已登记（可判定） */
        public boolean hasCruiseSpeed() {
            return !Double.isNaN(cruiseSpeedMs) && Double.isFinite(cruiseSpeedMs) && cruiseSpeedMs > 0;
        }
    }
}
