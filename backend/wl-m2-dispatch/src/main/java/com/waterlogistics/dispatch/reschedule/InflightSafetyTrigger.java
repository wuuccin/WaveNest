package com.waterlogistics.dispatch.reschedule;

/**
 * 在飞安全复核的触发来源——逐字对齐《84-E组算法方案》§7.3 {@code trigger_source} 五值。
 *
 * <p><b>本批只用「空域或气象」一值</b>：气象超限告警（M4 检测 → {@code alert.event}）
 * 到达后，对处于去程中与末段交接中的在飞订单做一次安全复核。其余四值先定义不接线，
 * 避免后续再改枚举而牵动已合入的调用方。
 *
 * <p>为什么五值必须一次定义齐：{@code trigger_source} 是落库与对外口径共用的字段，
 * 分两批加值会让同一列出现两种取值集合，历史数据的可比性会断。
 */
public enum InflightSafetyTrigger {

    /** 船侧意图变更（6b 改向分支，由 M2 判据产出） */
    INTENT_CHANGE("意图变更"),

    /** G 组告警（偏航、低电量、失联等检测项的告警出口） */
    ALERT_FROM_GROUP_G("G 组告警"),

    /** 电量包线破限（剩余电量已无法覆盖返航余量） */
    BATTERY_ENVELOPE_BREACH("电量包线破限"),

    /** 失联超时（遥测中断超过阈值） */
    LINK_LOSS_TIMEOUT("失联超时"),

    /** 空域或气象（临时禁飞、风速超限；本批唯一接线值） */
    AIRSPACE_OR_WEATHER("空域或气象");

    private final String value;

    InflightSafetyTrigger(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文取值（与《84》§7.3 原文逐字一致） */
    public String getValue() {
        return value;
    }
}
