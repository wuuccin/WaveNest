package com.waterlogistics.algo.meeting.model;

/**
 * 交接结果——《81-B组算法方案》§2.3 第（6）项：成功／失败（失败含重试／悬停等待结论）。
 */
public enum HandoverResult {

    /** 交接完成 */
    SUCCESS("成功"),

    /** 交接失败（含重试／悬停等待之后仍失败的结论，由 M2 决定返航或转应急迫停） */
    FAILED("失败");

    private final String value;

    HandoverResult(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("交接结果口径词不能为空：" + name());
        }
        this.value = value;
    }

    /** 返回对外口径的结果词 */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * 按对外口径词解析枚举。
     *
     * @throws IllegalArgumentException 入参为空/空白或无匹配口径词
     */
    public static HandoverResult fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("交接结果不能为空");
        }
        String normalized = value.strip();
        for (HandoverResult result : values()) {
            if (result.value.equals(normalized)) {
                return result;
            }
        }
        throw new IllegalArgumentException("未知交接结果："
                + value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", "?"));
    }
}
