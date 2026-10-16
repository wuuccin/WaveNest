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
            double maxPayloadKg) {
    }
}
