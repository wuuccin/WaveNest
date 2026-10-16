package com.waterlogistics.dispatch.order;

/**
 * 订单优先级（订单级）（M2 · 卢肄文）。
 *
 * <p>落库到 {@code order_task.priority}，取值域与列注释一致：
 * {@code NORMAL/URGENT/EMERGENCY}（{@code 37} §5.4，C-09）。<b>不是</b>中文状态词，
 * 不要与 {@code 23} 的中文状态词混用。
 *
 * <p>落库值即枚举名，故本枚举不做中文映射。
 */
public enum OrderPriority {

    /** 常规 */
    NORMAL,

    /** 加急 */
    URGENT,

    /** 应急 */
    EMERGENCY;

    /**
     * 按落库值解析。
     *
     * @param code 取值；{@code null} 或空白按缺省 {@link #NORMAL} 处理（与列默认值一致）
     * @return 对应优先级
     * @throws IllegalArgumentException 取值不在取值域内时抛出（不静默兜底）
     */
    public static OrderPriority fromCode(String code) {
        if (code == null || code.isBlank()) {
            return NORMAL;
        }
        for (OrderPriority priority : values()) {
            if (priority.name().equalsIgnoreCase(code.trim())) {
                return priority;
            }
        }
        throw new IllegalArgumentException("未知订单优先级：" + code);
    }
}
