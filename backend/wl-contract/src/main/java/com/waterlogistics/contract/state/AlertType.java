package com.waterlogistics.contract.state;

/**
 * 告警类型——依据《22-接口契约》v1.2 §2「偏差与告警」行，
 * 枚举以《37-M6-数据库》§5.12 {@code alert} 表为准。
 *
 * <p>前五类由 M4 检测产出：M4 消费 {@code data.weather} 并在模块层执行检测与判级，
 * M2 只做决策、不检测、不产告警（U-02 裁定）。
 *
 * <p>后三类为 M2 等待原因透出告警（11-05 序号 2 宋冰鑫）：订单进入等待态时由
 * M2 直接写 alert 表并发 alert.event，不走 M4 检测链路。alert_type 列沿用现有
 * VARCHAR(32)，不新增表、不新增列。
 */
public enum AlertType {

    /** 失联（心跳超过判定窗口） */
    LOST_LINK("失联"),

    /** 低电量 */
    LOW_BATTERY("低电量"),

    /** 偏航 */
    DEVIATION("偏航"),

    /** 气象超限 */
    WEATHER_EXCEED("气象超限"),

    /** 链路异常 */
    LINK_ERROR("链路异常"),

    /** 等待装货超时（订单进入 WAIT_CARGO 等待，货物所在巢时隙锁超时未释放） */
    WAIT_CARGO_TIMEOUT("装货超时"),

    /** 等待资源（订单因资源不足转入 WAIT_RESOURCE 等待队列） */
    WAIT_RESOURCE("等待资源"),

    /** 货到巢超时（货物已到巢但超时未被无人机取走） */
    CARGO_NEST_TIMEOUT("货到巢超时");

    private final String value;

    AlertType(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文取值 */
    public String getValue() {
        return value;
    }

    /** 按中文取值解析枚举 */
    public static AlertType fromValue(String value) {
        for (AlertType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知告警类型：" + value);
    }
}
