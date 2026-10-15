package com.waterlogistics.contract.state;

/**
 * 货物状态机——6 态，依据《23-状态机定义》v0.6 §6。
 *
 * <p>落库与传输用 {@link #getValue()}（中文状态词），对应 {@code goods.status}。
 * "已装机"与"已交付"/"已回收"互斥须遵循约束：货物装机后绑死在该机上，空中不可转移。
 */
public enum GoodsStatus {

    /** 拣选、打包中 */
    NOT_READY("未就绪"),

    /** 备好并通过检查，满足派单前置门 */
    READY("已就绪"),

    /** 随无人机离巢；此后货物绑死在该机上，空中不可转移 */
    LOADED("已装机"),

    /** 与船交接完成 */
    DELIVERED("已交付"),

    /** 随原机落巢后被系统回收，状态转为可用，构成重派唯一入口 */
    RECYCLED("已回收"),

    /** 随无人机灭失，订单异常终止 */
    LOST("灭失");

    private final String value;

    GoodsStatus(String value) {
        this.value = value;
    }

    /** 返回落库与传输用的中文状态词 */
    public String getValue() {
        return value;
    }

    /** 按中文状态词解析枚举 */
    public static GoodsStatus fromValue(String value) {
        for (GoodsStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知货物状态：" + value);
    }
}
