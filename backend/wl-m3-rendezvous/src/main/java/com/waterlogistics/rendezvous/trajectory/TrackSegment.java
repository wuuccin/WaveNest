package com.waterlogistics.rendezvous.trajectory;

/**
 * 航迹分段标签——大屏「无人机三段航迹」分段着色的取词口径（《36-M5-可视化》§3）。
 *
 * <p>取值照《43-编码分工说明》三段表述「去程／末段交接／返程」，
 * 与订单状态词「去程中／末段交接中／返航中」（《23-状态机定义》）一一段对应。
 */
public enum TrackSegment {

    /** 机巢 → 会面点 */
    OUTBOUND("去程"),

    /** 会面点 → 交接完成位置（甲板上方控制段） */
    TERMINAL("末段交接"),

    /** 交接完成位置 → 归巢落点 */
    RETURN("返程");

    private final String value;

    TrackSegment(String value) {
        this.value = value;
    }

    /** 返回分段着色的对端口径词 */
    public String getValue() {
        return value;
    }

    /** 按对端口径词解析枚举 */
    public static TrackSegment fromValue(String value) {
        for (TrackSegment segment : values()) {
            if (segment.value.equals(value)) {
                return segment;
            }
        }
        throw new IllegalArgumentException("未知航迹分段：" + value);
    }
}
