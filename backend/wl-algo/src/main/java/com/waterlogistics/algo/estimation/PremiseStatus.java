package com.waterlogistics.algo.estimation;

/**
 * 预测前提的有效性状态（A 组 · estimation 子包）。
 *
 * <p>预测的成立依赖一组前提（船保持当前对地航向/航速、无新的变向意图等，
 * 见 {@code 80-A组算法方案} §3.2.8 前提监测）。本枚举是<b>前提有效性的算法层取值口径</b>，
 * 由模块层原样写入 {@code ShipTrajFrame.premise}（该字段在 {@code wl-contract} 中
 * 暂为 {@code String} 透传，注释标注「取值口径待 A 组补齐后回填」）。
 *
 * <p>取值口径（三值，最小可用集合）：
 * <ul>
 *   <li>{@link #VALID} —— 前提成立，轨迹可采信；</li>
 *   <li>{@link #INVALID} —— 前提已被推翻（如检测到变向／状态跳变），轨迹不应被下游采信；</li>
 *   <li>{@link #UNKNOWN} —— 证据不足（冷启动、观测中断），既不能确认也不能否定。</li>
 * </ul>
 *
 * <p>今天是简化版：只要拿到一条有效 AIS 即返回 {@link #VALID}；完整的前提监测
 * （事实层／统计层 ＋ P 判据）属精算版，排期顺延（《编码分工表》§六）。
 */
public enum PremiseStatus {

    /** 前提成立 */
    VALID("VALID"),

    /** 前提已被推翻 */
    INVALID("INVALID"),

    /** 证据不足（冷启动／观测中断） */
    UNKNOWN("UNKNOWN");

    private final String code;

    PremiseStatus(String code) {
        this.code = code;
    }

    /**
     * 返回写入 {@code ShipTrajFrame.premise} 的字符串编码。
     *
     * <p>固定为大写英文枚举名，保持线上口径稳定、与 {@code 23} 的中文状态词体系不混用。
     */
    public String code() {
        return code;
    }

    /** 按编码解析枚举，未知编码抛异常（不静默兜底，避免脏值被当成 VALID）。 */
    public static PremiseStatus fromCode(String code) {
        for (PremiseStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知前提状态编码：" + code);
    }
}
