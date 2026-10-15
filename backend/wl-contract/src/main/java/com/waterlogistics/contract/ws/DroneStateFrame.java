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
 * @param lon     经度
 * @param lat     纬度
 * @param battery 剩余电量，百分比（0–100）
 * @param phase   飞行阶段
 * @param ts      观测时刻，Unix 毫秒
 */
public record DroneStateFrame(

        String uavId,

        double lon,

        double lat,

        double battery,

        String phase,

        long ts) {
}
