package com.waterlogistics.rendezvous.handover;

import com.waterlogistics.rendezvous.support.WireText;
import lombok.Getter;

import java.util.regex.Pattern;



/**
 * 末段四子阶段——接近 → 匹配 → 交接 → 脱离（《81-B组算法方案》§3.6、《32-M3-动态会面》
 * §3.4）。四子阶段为 {@code 末段交接中} 的<b>内部过程</b>，不建独立任务状态
 * （《23-状态机定义》v0.4 裁定）。
 *
 * <p>落库与传输一律使用 {@link #getValue()}（中文阶段词）；{@code ordinal()} 与
 * {@code name()} 不得作为持久化或传输取值。
 */
public enum HandoverPhase {

    /**
     * 从邻域外缘收敛到目标相对位置附近
     */
    APPROACH("接近"),

    /**
     * 相对速度趋近于零、航向与船对齐
     */
    MATCH("匹配"),

    /**
     * 稳定悬停于甲板上方，执行投放
     */
    TRANSFER("交接"),

    /**
     * 拉升离开、恢复巡航
     */
    DEPART("脱离");

    /**
     * 对外口径的阶段词（中文）；落库与传输一律用它
     */
    @Getter
    private final String value;





    HandoverPhase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("末段子阶段口径词不能为空：" + name());
        }
        this.value = value;
    }





    /**
     * 按对外口径词解析枚举。
     *
     * @param value 对外口径词（中文阶段词；空白或未知即拒绝）
     * @return 对应的末段子阶段
     * @throws IllegalArgumentException 入参为空/空白或无匹配口径词
     */
    public static HandoverPhase fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("末段子阶段不能为空");
        }
        String normalized = WireText.normalizeToken(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("末段子阶段不能为空");
        }
        for (HandoverPhase phase : HandoverPhase.values()) {
            if (phase.value.equals(normalized)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("未知末段子阶段：" + WireText.escapeForLog(normalized));
    }
}
