package com.waterlogistics.contract.rest;

/**
 * 派单闸门状态——依据《22-接口契约》v1.2 §3.2（源于《29》§2 第 17 项，D 组功能 12）。
 *
 * <p>落库与传输用 {@link #getValue()}（中文取值）。
 */
public enum GateStatus {

    /** 正常 */
    NORMAL("正常"),

    /** 熔断中 */
    BROKEN("熔断中");

    private final String value;

    GateStatus(String value) {
        this.value = value;
    }

    /** 返回传输用的中文取值 */
    public String getValue() {
        return value;
    }

    /** 按中文取值解析枚举 */
    public static GateStatus fromValue(String value) {
        for (GateStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知闸门状态：" + value);
    }
}
