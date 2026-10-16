package com.waterlogistics.rendezvous.handover;

/**
 * 末段四子阶段——接近 → 匹配 → 交接 → 脱离（《81-B组算法方案》§3.6、《32-M3-动态会面》
 * §3.4）。四子阶段为 {@code 末段交接中} 的<b>内部过程</b>，不建独立任务状态
 * （《23-状态机定义》v0.4 裁定）。
 */
public enum HandoverPhase {

    /** 从邻域外缘收敛到目标相对位置附近 */
    APPROACH("接近"),

    /** 相对速度趋近于零、航向与船对齐 */
    MATCH("匹配"),

    /** 稳定悬停于甲板上方，执行投放 */
    TRANSFER("交接"),

    /** 拉升离开、恢复巡航 */
    DEPART("脱离");

    private final String value;

    HandoverPhase(String value) {
        this.value = value;
    }

    /** 返回对外口径的阶段词 */
    public String getValue() {
        return value;
    }

    /** 按对外口径词解析枚举 */
    public static HandoverPhase fromValue(String value) {
        for (HandoverPhase phase : values()) {
            if (phase.value.equals(value)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("未知末段子阶段：" + value);
    }
}
