package com.waterlogistics.algo.meeting.model;

/**
 * 起飞时刻不可行原因——取值只列文档已见两支（《81-B组算法方案》§3.3、
 * 《22-接口契约》§2.1.2），不自行扩表；如需补值随会签补全。
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

    /** 按对外口径词解析枚举 */
    public static TakeoffInfeasibleReason fromValue(String value) {
        for (TakeoffInfeasibleReason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知起飞不可行原因：" + value);
    }
}
