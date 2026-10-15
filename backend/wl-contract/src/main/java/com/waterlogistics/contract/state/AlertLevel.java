package com.waterlogistics.contract.state;

/**
 * 告警级别——依据《22-接口契约》v1.2 §2「偏差与告警」行，
 * 枚举以《37-M6-数据库》§5.12 {@code alert} 表为准。
 */
public enum AlertLevel {

    /** 提示 */
    INFO("提示"),

    /** 警告 */
    WARN("警告"),

    /** 严重 */
    CRITICAL("严重");

    private final String value;

    AlertLevel(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文取值 */
    public String getValue() {
        return value;
    }

    /** 按中文取值解析枚举 */
    public static AlertLevel fromValue(String value) {
        for (AlertLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知告警级别：" + value);
    }
}
