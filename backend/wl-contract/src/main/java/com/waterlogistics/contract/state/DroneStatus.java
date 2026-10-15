package com.waterlogistics.contract.state;

/**
 * 无人机状态机——9 态，依据《23-状态机定义》v0.6 §7。
 *
 * <p>落库与传输用 {@link #getValue()}（中文状态词），对应 {@code drone.status}。
 * 只有 {@link #IDLE} 可被 L5 占位、可展开为候选（《23》§7 约定 2）；
 * 充电中归入 {@link #MAINTENANCE}（2026-10-14 裁定，不新增独立充电状态）。
 *
 * <p>失联（心跳超过判定窗口）不等于灭失，持续超过灭失判定窗口才推定灭失。
 */
public enum DroneStatus {

    /** 可进入候选，唯一可被 L5 占位的状态 */
    IDLE("空闲"),

    /** L5 占位成功至起飞前的窗口 */
    PRE_OCCUPIED("已预占"),

    /** 从起飞到交接完成（含去程、末段） */
    IN_MISSION("执行任务中"),

    /** 交接完成至落巢 */
    RETURNING("返航中"),

    /** 临时停靠他巢，归属不变；迫停只改 currentNestId，不改 homeNestId */
    DIVERTED("迫停"),

    /** 落在应急迫降点 */
    EMERGENCY_LANDED("迫降"),

    /** 设备异常，须检修 */
    FAULT("故障"),

    /** 计划检修、保养；充电中归入本状态 */
    MAINTENANCE("维护"),

    /** 心跳超过判定窗口未收到 */
    LOST_LINK("失联");

    private final String value;

    DroneStatus(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /** 是否可展开为调度候选（只取"空闲"） */
    public boolean isCandidate() {
        return this == IDLE;
    }

    /** 按中文状态词解析枚举 */
    public static DroneStatus fromValue(String value) {
        for (DroneStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知无人机状态：" + value);
    }
}
