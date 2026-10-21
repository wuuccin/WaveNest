package com.waterlogistics.algo.meeting.model;

/**
 * 无解原因——照《81-B组算法方案》§2.3 第（1）项，取值不得自造。
 *
 * <p>透传口径：落库与传输一律使用 {@link #getValue()}（中文本词）。本类处在纯计算层
 * （不引 Jackson），序列化转换由装配层/消息层负责；{@link #toString()} 同取对外口径词，
 * 避免日志里落到英文枚举名。
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

    /** 对外口径词即 {@code toString()}，避免日志误用英文枚举名 */
    @Override
    public String toString() {
        return value;
    }

    /**
     * 按对外口径词解析枚举。
     *
     * @throws IllegalArgumentException 入参为空/空白或无匹配口径词
     */
    public static NoSolutionReason fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("无解原因不能为空");
        }
        String normalized = value.strip();
        for (NoSolutionReason reason : values()) {
            if (reason.value.equals(normalized)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知无解原因：" + value);
    }
}
