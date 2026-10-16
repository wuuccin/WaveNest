package com.waterlogistics.algo.meeting.model;

/**
 * 无解原因——照《81-B组算法方案》§2.3 第（1）项，取值不得自造。
 *
 * <p>透传口径：落库与传输一律使用 {@link #getValue()}（中文本词）。
 */
public enum NoSolutionReason {

    /** 在允许时限 T_max 内追不上（《81》§3.1 判据 2：g(t) 恒负） */
    CANNOT_CATCH("追不上"),

    /** 地理或约束上不可达 */
    UNREACHABLE("不可达"),

    /** 其他（含入参退化：点列为空、可求解时间窗为空） */
    OTHER("其他");

    private final String value;

    NoSolutionReason(String value) {
        this.value = value;
    }

    /** 返回对外口径的无解原因词（《81》§2.3） */
    public String getValue() {
        return value;
    }

    /** 按对外口径词解析枚举 */
    public static NoSolutionReason fromValue(String value) {
        for (NoSolutionReason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知无解原因：" + value);
    }
}
