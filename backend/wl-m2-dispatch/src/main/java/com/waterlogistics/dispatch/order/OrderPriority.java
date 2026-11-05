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
    NORMAL(1),

    /** 加急 */
    URGENT(2),

    /** 应急 */
    EMERGENCY(3);

    private final int rank;

    OrderPriority(int rank) {
        this.rank = rank;
    }

    /**
     * 取值序（{@code NORMAL=1 < URGENT=2 < EMERGENCY=3}），<b>数值越大越优先</b>。
     *
     * <p><b>为什么不在枚举里写 {@code compareTo}</b>：{@link Enum#compareTo} 已被
     * 声明顺序占用（{@code ordinal} 序），覆写不了；而「声明顺序」是个会随重构漂移的
     * 实现细节——今天恰好把 EMERGENCY 写在最后是顺手，明天有人按字母序重排枚举，
     * 队列取单顺序就会静默反过来。<b>显式 {@code rank} 是唯一不会被重排影响的契约</b>。
     *
     * <p>消费方：
     * <ul>
     *   <li>队列取单 {@code ORDER BY FIELD(priority,'EMERGENCY','URGENT','NORMAL')}
     *       （《11-05 任务清单》序号 2，张睿哲）；</li>
     *   <li>候选比较器的延误惩罚权重（同序号 2）。两处都要「越紧急越大」的同一口径，
     *       故取值只在这里定义一次，不在 SQL 与 Java 里各写一套。</li>
     * </ul>
     *
     * @return 取值序，{@code NORMAL=1}／{@code URGENT=2}／{@code EMERGENCY=3}
     */
    public int rank() {
        return rank;
    }

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
