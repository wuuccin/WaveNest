package com.waterlogistics.contract.state;

/**
 * 订单状态机——12 态，依据《23-状态机定义》v0.6 §3，不得自造状态词。
 *
 * <p>落库与传输一律使用 {@link #getValue()}（中文状态词），与
 * {@code order_task.status} 的列注释完全一致（《37-M6-数据库》§5.4）。
 * 禁用写法（如"待派发""已分配""派单中""执行中""完成"）见《23》§9。
 *
 * <p>正向唯一路径：待受理 → 已受理待唤醒 → 待调度 → 调度中 → 已派发
 * →（去程中 → 末段交接中 → 返航中）→ 已完成。
 */
public enum OrderStatus {

    /** 订单已创建，接单判定尚未完成 */
    PENDING_ACCEPT("待受理"),

    /**
     * 受理但不派单。唤醒原因四类（等船／等货／闸门熔断／数据不足）由
     * 算法层 {@code AdmissionResult.wakeReason} 给出（《23》§3 映射表）。
     */
    ACCEPTED_WAIT_WAKE("已受理待唤醒"),

    /** 已满足派单前置门，进入派单队列，等待本批求解 */
    WAIT_DISPATCH("待调度"),

    /** 第 2–5 步求解中 */
    DISPATCHING("调度中"),

    /** 起飞时刻已回填，任务已下达 */
    DISPATCHED("已派发"),

    /** 第 6 步 */
    OUTBOUND("去程中"),

    /** 第 7 步 */
    HANDOVER("末段交接中"),

    /** 第 8 步 */
    RETURNING("返航中"),

    /** 交接成功 */
    FINISHED("已完成"),

    /** 业务边界不满足，或第 3／5 步无可行组合 */
    REJECTED("已拒绝"),

    /** 迫降野外，转人工回收 */
    FAILED("失败"),

    /** 货物灭失 */
    ABORTED("异常终止");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /**
     * 是否为终止态。已拒绝／失败／异常终止互斥，进入后不再流转（《23》§3 约束 3）。
     */
    public boolean isTerminal() {
        return this == REJECTED || this == FAILED || this == ABORTED;
    }

    /**
     * 按中文状态词解析枚举。
     *
     * @throws IllegalArgumentException 传入枚举值不在本节 12 态之内时抛出
     */
    public static OrderStatus fromValue(String value) {
        for (OrderStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态：" + value);
    }
}
