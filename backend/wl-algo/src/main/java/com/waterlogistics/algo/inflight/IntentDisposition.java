package com.waterlogistics.algo.inflight;

/**
 * 6b 出口的处置结论，依据《29-各组算法层接口定义》§3.5.1(1b)。
 *
 * <p>{@code 29} 原文：「<b>6b 出口</b>：处置结论 | 继续追 ／ 放弃本次会面 |
 * 功能 9（继续追）／功能 10（放弃本次会面）」。
 * 并注：「6a／6b 是功能 17 的两个<b>出口</b>，不是两个算法」。
 *
 * <p><b>今天只定义类型，不接线。</b>由「意图变更持续监测信号」到本结论的<b>判档</b>
 * 属功能 9（飞行中重调度）的职责，且要综合电量、剩余航程、返程基线等，
 * 并非单纯由一个航向差阈值决定——故此处不做 {@code signal → disposition} 的映射，
 * 避免把功能 9 的决策提前下沉到算法层。16 号在
 * {@code dispatch.reschedule} 接链时再补。
 */
public enum IntentDisposition {

    /** 会面前提仍成立，继续追赶（交功能 9） */
    CONTINUE_CHASE("继续追"),

    /** 会面前提失效，放弃本次会面（交功能 10 返航复核与归巢资源判定） */
    ABANDON_MEET("放弃本次会面");

    private final String value;

    IntentDisposition(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /** 按中文状态词解析枚举 */
    public static IntentDisposition fromValue(String value) {
        for (IntentDisposition item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知 6b 处置结论：" + value);
    }
}
