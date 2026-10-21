package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.rendezvous.support.WireText;
import lombok.Getter;




/**
 * 航迹分段标签——大屏「无人机三段航迹」分段着色的取词口径（《36-M5-可视化》§3）。
 *
 * <p>取值照《43-编码分工说明》三段表述「去程／末段交接／返程」，
 * 与订单状态词「去程中／末段交接中／返航中」（《23-状态机定义》）一一段对应。
 */
public enum TrackSegment {

    /**
     * 机巢 → 会面点
     */
    OUTBOUND("去程"),

    /**
     * 会面点 → 交接完成位置（甲板上方控制段）
     */
    TERMINAL("末段交接"),

    /**
     * 交接完成位置 → 归巢落点
     */
    RETURN("返程");

    /**
     * 分段着色的对端口径词（中文）
     */
    @Getter
    private final String value;





    TrackSegment(String value) {
        this.value = value;
    }

    /**
     * 固定排序秩，与声明顺序解耦，供分段拼接校验使用。
     *
     * @return 去程 0、末段交接 1、返程 2
     */
    public int rank() {
        return switch (this) {
            case OUTBOUND -> 0;
            case TERMINAL -> 1;
            case RETURN -> 2;
        };
    }






    /**
     * 按对端口径词解析枚举。
     *
     * @param value 对端口径词（中文分段词；首尾空白忽略，空白或未知即拒绝）
     * @return 对应的航迹分段
     * @throws IllegalArgumentException 入参为空/空白或无匹配口径词
     */
    public static TrackSegment fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("航迹分段不能为空");
        }
        String normalized = WireText.normalizeToken(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("航迹分段不能为空");
        }
        for (TrackSegment segment : TrackSegment.values()) {
            if (segment.value.equals(normalized)) {
                return segment;
            }
        }
        throw new IllegalArgumentException("未知航迹分段：" + WireText.escapeForLog(normalized));
    }
}
