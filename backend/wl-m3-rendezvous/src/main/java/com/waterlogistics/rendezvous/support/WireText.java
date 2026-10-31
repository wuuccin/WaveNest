package com.waterlogistics.rendezvous.support;

import java.text.Normalizer;
import java.util.regex.Pattern;



/**
 * 对外口径词的归一与日志脱敏工具，供枚举解析共用同一套字符规则。
 */
public final class WireText {

    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}\\p{Cf}]");

    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");





    private WireText() {
    }





    /**
     * 去掉全部空白、分隔与格式字符，用于中文口径词的精确匹配。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 归一后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String normalizeToken(CharSequence value) {
        if (value == null) {
            return null;
        }
        return WireText.SPACING.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).replaceAll("");
    }





    /**
     * 是否含日志/协议不允许的控制、格式或分隔字符。
     *
     * @param value 待检查文本
     * @return 含不安全字符时为 {@code true}
     */
    public static boolean isUnsafe(CharSequence value) {
        return value != null && WireText.UNSAFE.matcher(value).find();
    }





    /**
     * 将控制字符、格式字符与行/段分隔符替换为 {@code ?}，防止日志伪造或终端转义。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 脱敏后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String escapeForLog(CharSequence value) {
        return value == null ? null : WireText.UNSAFE.matcher(value).replaceAll("?");
    }

    /**
     * 脱敏并截断，避免超长外部输入把日志或异常消息撑爆。
     *
     * @param value     原始文本，可为 {@code null}
     * @param maxLength 保留的最大字符数
     * @return 脱敏且不超过 {@code maxLength} 的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String escapeForLog(CharSequence value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("截断长度不得为负，实际 " + maxLength);
        }
        if (value.length() <= maxLength) {
            return WireText.escapeForLog(value);
        }
        int keep = Math.max(0, maxLength - 3);
        return WireText.escapeForLog(value.subSequence(0, keep)) + "...";
    }

    /**
     * 校验并归一标识符：去除首尾空白后，拒绝空串、超长、内部空白与不安全字符。
     *
     * @param value     原始标识符
     * @param field     字段名，用于错误消息
     * @param maxLength 允许的最大长度
     * @return 归一后的标识符
     */
    public static String requireIdentifier(CharSequence value, String field, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String normalized = value.toString().strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || WireText.UNSAFE.matcher(normalized).find()
                || normalized.chars().anyMatch(Character::isWhitespace)
                || normalized.chars().anyMatch(Character::isSpaceChar)) {
            throw new IllegalArgumentException(field + " 非法：长度、空白或字符不在允许范围内");
        }
        return normalized;
    }
}