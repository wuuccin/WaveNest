package com.waterlogistics.contract.ws;

/**
 * 无人机状态帧 {@code drone_state}——依据《22-接口契约》v1.2 §4（位置、电量、飞行阶段）。
 *
 * <p>数据源为 M1 接入的遥测经 M4 飞行语义级清洗后写入的共享数据源（《22》§5 横切行，U-03 裁定）。
 *
 * <p><b>待裁决</b>：飞行阶段的取值口径未在本项目任一文档中枚举。实现时若需「去程／末段／返航」，
 * 复用《23》§4 的任务状态词以保证术语一致；若需末段四子阶段，复用 {@code handoverPhase}
 * 的「接近／匹配／交接／脱离」。定案前不得自造第三套写法。
 *
 * <p><b>T1-1 扩字段</b>（10-27 契约与地基）：新增航向／在飞订单号两项，均为可空——
 * 值来源为遥测 {@code heading} 与当前任务绑定关系（见 T1-2），无任务时订单号为空是正确值。
 *
 * @param uavId        无人机编号
 * @param lon          经度
 * @param lat          纬度
 * @param battery      剩余电量，百分比（0–100）
 * @param phase        飞行阶段
 * @param ts           观测时刻，Unix 毫秒
 * @param heading      航向，度（0–360）；遥测缺失时为 {@code null}
 * @param activeOrderId 在飞订单号；无人机未绑定任务时为 {@code null}
 */
public record DroneStateFrame(

        String uavId,

        double lon,

        double lat,

        double battery,

        String phase,

        long ts,

        Double heading,

        String activeOrderId) {

    /**
     * 兼容构造器：保留 T1-1 之前的六参签名，新字段留空。
     *
     * <p>构造点分散在 wl-app 与 wl-m4，序号 2 填值前逐处迁移，互不阻塞。
     */
    public DroneStateFrame(String uavId, double lon, double lat,
                           double battery, String phase, long ts) {
        this(uavId, lon, lat, battery, phase, ts, null, null);
    }
}
