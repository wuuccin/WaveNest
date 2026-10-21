package com.waterlogistics.rendezvous.support;

import java.util.regex.Pattern;

/**
 * 对外口径词的归一与日志脱敏工具，供枚举解析共用同一套字符规则。
 */
public final class WireText {

    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}\\p{Cf}]");

    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private WireText() {
    }

    /**
     * 去掉全部空白、分隔与格式字符，用于中文口径词的精确匹配。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 归一后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String normalizeToken(String value) {
        return value == null ? null : SPACING.matcher(value).replaceAll("");
    }

    /**
     * 将控制字符、格式字符与行/段分隔符替换为 {@code ?}，防止日志伪造或终端转义。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 脱敏后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String escapeForLog(String value) {
        return value == null ? null : UNSAFE.matcher(value).replaceAll("?");
    }
}