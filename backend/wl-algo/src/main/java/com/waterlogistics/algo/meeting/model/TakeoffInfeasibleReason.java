package com.waterlogistics.algo.meeting.model;

/**
 * 起飞时刻不可行原因——取值只列文档已见两支（《81-B组算法方案》§3.3、
 * 《22-接口契约》§2.1.2），不自行扩表；如需补值随会签补全。
 *
 * <p>透传口径：落库与传输一律使用 {@link #getValue()}（中文本词）。本类处在纯计算层
 * （不引 Jackson），序列化转换由装配层/消息层负责；{@link #toString()} 同取对外口径词。
 */
public enum TakeoffInfeasibleReason {

    /** 反推所得起飞时刻早于当前时刻——精算时长已超出会面窗口可容忍的余量（《81》§3.3 要点 2） */
    BEFORE_NOW("早于当前时刻"),

    /** 不动点阻尼后仍不收敛、候选淘汰（《22》§2.1.2 增补取值，承接《83》D-11 出口语义） */
    ITERATION_NOT_CONVERGED("迭代不收敛");

    private final String value;

    TakeoffInfeasibleReason(String value) {
        this.value = value;
    }

    /** 返回对外口径的原因词 */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * 按对外口径词解析枚举。
     *
     * @throws IllegalArgumentException 入参为空/空白或无匹配口径词
     */
    public static TakeoffInfeasibleReason fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("起飞不可行原因不能为空");
        }
        String normalized = value.strip();
        for (TakeoffInfeasibleReason reason : values()) {
            if (reason.value.equals(normalized)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知起飞不可行原因：" + value);
    }
}
