package com.waterlogistics.contract.rest;

/**
 * 熔断来源——依据《22-接口契约》v1.2 §3.2。
 *
 * <p>人力冻结不覆盖自动熔断：解除人工冻结后若自动熔断仍有效，状态仍为熔断中，
 * 是否恢复派单由 M2 确认。
 */
public enum GateSource {

    /** 人工冻结 */
    MANUAL("人工冻结"),

    /** 自动熔断 */
    AUTO("自动熔断");

    private final String value;

    GateSource(String value) {
        this.value = value;
    }

    /** 返回传输用的中文取值 */
    public String getValue() {
        return value;
    }

    /** 按中文取值解析枚举 */
    public static GateSource fromValue(String value) {
        for (GateSource source : values()) {
            if (source.value.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知熔断来源：" + value);
    }
}
