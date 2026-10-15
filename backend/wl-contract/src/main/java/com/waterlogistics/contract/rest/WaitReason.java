package com.waterlogistics.contract.rest;

/**
 * 模块层等待原因三值枚举——依据《22-接口契约》v1.2 §3.1（来源《35-M5》v1.1）。
 *
 * <p>仅在订单处于"已受理待唤醒"时有值；离开等待态返回 null 并清空前端展示。
 *
 * <p>投影规则：算法层 {@code wakeReason} 为四类（等船／等货／闸门熔断／数据不足），
 * 本处合并为三值——"闸门熔断"与"数据不足"同为 {@link #GATE_OR_DATA}，
 * 文案显示"闸门熔断或数据不足"，真实闸门态另由 {@code dispatchGate} 独立回显。
 * 算法层四类为权威，不因本投影改变。
 */
public enum WaitReason {

    /** 船尚未驶入系统服务航段 */
    WAIT_SHIP("WAIT_SHIP"),

    /** 货物未就绪 */
    WAIT_CARGO("WAIT_CARGO"),

    /** 派单闸门处于熔断中，或关键数据不足暂缓派机 */
    GATE_OR_DATA("GATE_OR_DATA");

    private final String code;

    WaitReason(String code) {
        this.code = code;
    }

    /** 返回线上传输用的英文编码 */
    public String getCode() {
        return code;
    }

    /** 按英文编码解析枚举 */
    public static WaitReason fromCode(String code) {
        for (WaitReason reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知等待原因：" + code);
    }
}
