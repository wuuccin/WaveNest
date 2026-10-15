package com.waterlogistics.contract.state;

/**
 * 告警类型——依据《22-接口契约》v1.2 §2「偏差与告警」行，
 * 枚举以《37-M6-数据库》§5.12 {@code alert} 表为准。
 *
 * <p>四类告警统一由 M4 检测产出：M4 消费 {@code data.weather} 并在模块层执行检测与判级，
 * M2 只做决策、不检测、不产告警（U-02 裁定）。
 */
public enum AlertType {

    /** 失联（心跳超过判定窗口） */
    LOST_LINK("失联"),

    /** 低电量 */
    LOW_BATTERY("低电量"),

    /** 偏航 */
    DEVIATION("偏航"),

    /** 气象超限 */
    WEATHER_EXCEED("气象超限"),

    /** 链路异常 */
    LINK_ERROR("链路异常");

    private final String value;

    AlertType(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文取值 */
    public String getValue() {
        return value;
    }

    /** 按中文取值解析枚举 */
    public static AlertType fromValue(String value) {
        for (AlertType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知告警类型：" + value);
    }
}
