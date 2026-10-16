package com.waterlogistics.algo.inflight;

/**
 * 返航落点三级，依据《84-E组算法方案》第4章「功能10 · 返航复核与归巢资源判定」。
 *
 * <p>《84》第5章「跨功能共用口径（易混点，勿写错）」逐字：
 * 「<b>返航降级顺序</b>｜回原巢 → 迫停他巢 → 迫降野外。<b>按序降级，迫降是最后一级，不可跳级</b>」。
 *
 * <p>故本枚举的<b>声明顺序即降级顺序</b>；「不可跳级」这条口径由
 * {@link HomecomingReview} 的顺序求值 ＋ 单测钉死，<b>不做成可配置策略链</b>——
 * 可配置链等于留了「今天直接跳第三级」的口子。
 *
 * <p>另记《84》第5章易混点：「<b>返航目标</b>｜<b>唯一化＝原巢</b>。"偏离大／别处更近"不是改投理由」——
 * 本枚举只表达<b>降级次序</b>；第二级选中哪一巢属「准入」不属「择优」，见 {@link NestCandidate}。
 */
public enum HomecomingTier {

    /** 第一级：回原巢（归巢目标唯一化，只有这一级是「回原巢」） */
    RETURN_HOME_NEST("回原巢"),

    /** 第二级：迫停他巢（须过准入判定；占位请求只在本级发出） */
    FORCE_STOP_OTHER_NEST("迫停他巢"),

    /** 第三级：迫降野外（最后一级，不可跳级） */
    EMERGENCY_LAND_FIELD("迫降野外");

    private final String value;

    HomecomingTier(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /** 按中文状态词解析枚举 */
    public static HomecomingTier fromValue(String value) {
        for (HomecomingTier item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("未知返航落点级别：" + value);
    }
}