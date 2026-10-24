package com.waterlogistics.dispatch.prediction;

/**
 * 预测实现的选择（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>F4 要求「切精算，但保留五分钟内可回退的匀速版」：本枚举就是那个开关——
 * 由属性 {@code wavenest.m2.prediction.mode} 取值，改一行重启即切换。
 *
 * @see PredictionProfile
 */
public enum PredictionMode {

    /** 精算版：观测滤波 ＋ 曲线坐标 ＋ 协方差传播 ＋ 横向 OU（{@code 80} §3.2.4–§3.2.7）。 */
    PRECISE("precise"),

    /** 匀速版：二维匀速外推 ＋ 固定噪声带（既有基线，回退用）。 */
    CONSTANT("constant");

    private final String code;

    PredictionMode(String code) {
        this.code = code;
    }

    /** 配置文件里的取值。 */
    public String code() {
        return code;
    }

    /**
     * 解析配置取值（大小写不敏感）。
     *
     * <p><b>认不出就回落匀速版</b>，而不是抛异常：演示现场写错一个字母就起不来，
     * 比退回一个已知能用的实现更糟；回落时由装配处打警告，便于发现笔误。
     *
     * @param code 配置取值，可为 {@code null}
     * @return 解析结果；无法识别时返回 {@link #CONSTANT}
     */
    public static PredictionMode fromCode(String code) {
        if (code != null) {
            for (PredictionMode mode : values()) {
                if (mode.code.equalsIgnoreCase(code.trim())) {
                    return mode;
                }
            }
        }
        return CONSTANT;
    }
}
