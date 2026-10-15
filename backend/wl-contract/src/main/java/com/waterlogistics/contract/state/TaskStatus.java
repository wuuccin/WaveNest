package com.waterlogistics.contract.state;

/**
 * 任务状态机——8 态，依据《23-状态机定义》v0.6 §4。
 *
 * <p>落库与传输用 {@link #getValue()}（中文状态词），对应 {@code mission.status}。
 * 任务状态只由"已派发"起算；飞行中途的 6b 意图变更、风速突变、临时禁飞
 * <b>不新增任务状态</b>，只触发调度侧重跑，状态仍停在"去程中"（《23》§4 约束 2）。
 */
public enum TaskStatus {

    /** 第 5 步精算与起飞时刻回填完成，任务方案已下发 M4 */
    DISPATCHED("已派发"),

    /** M4 确认起飞（第 6 步） */
    OUTBOUND("去程中"),

    /** M3 判定进入会面点邻域（第 7 步） */
    HANDOVER("末段交接中"),

    /** 交接完成确认，进入第 8 步 */
    RETURNING("返航中"),

    /** 落巢确认（原驻巢或他巢迫停均算任务闭环，落点另记） */
    FINISHED("已完成"),

    /** 返航复核判定回不了原驻巢，向近处机巢申请应急迫停并获准入 */
    DIVERTED("迫停中"),

    /** 无可借机位，转入应急迫降点，等待人工回收；对应订单进入"失败" */
    AWAITING_RECOVERY("迫降待回收"),

    /** 无人机灭失判定成立；货物"灭失"，订单"异常终止" */
    ABORTED("异常终止");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /** 是否为异常分支状态 */
    public boolean isAbnormal() {
        return this == DIVERTED || this == AWAITING_RECOVERY || this == ABORTED;
    }

    /** 按中文状态词解析枚举 */
    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态：" + value);
    }
}
