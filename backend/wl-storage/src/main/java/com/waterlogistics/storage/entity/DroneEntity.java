package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 无人机表 drone（37 §5.2）
 * 状态枚举值见 23 §7：空闲/已预占/执行任务中/返航中/迫停/迫降/故障/维护/失联
 */
@Data
@TableName("drone")
public class DroneEntity {

    /** 无人机标识 */
    @TableId(type = IdType.INPUT)
    private String droneId;

    /** 归属机巢（稳定「家」） */
    private String homeNestId;

    /** 当前所在机巢（动态；空中/迫降点时为 null） */
    private String currentNestId;

    /** 当前位置类型：原巢/空中/他巢临时停靠/迫降点 */
    private String positionType;

    /** 机型标识 */
    private String model;

    /** 最大载重（kg） */
    private BigDecimal maxPayload;

    /** 最大续航（秒） */
    private Integer maxEndurance;

    /**
     * 机型抗风上限（m/s）——机型能力包线「抗风等级」的定量落点（62 功能 3 输入
     * 「机型能力包线：抗风等级」，判据 {@code windclass_match}：机型抗风等级 ≥ 任务区最不利风级）。
     *
     * <p><b>为什么落在实例行而不另建机型表</b>：{@code 62} 把抗风等级归为**机型级**静态属性，
     * 但本库没有 {@code drone_model} 档案表，机型级属性唯一可落处就是 {@code drone} 行
     * （同 {@code max_payload}／{@code max_endurance} 的既有处理方式，均按机型冗余在实例行）。
     * 故同一 {@code model} 的各实例该列取同值；后续机型档案表落地时，本列改为按
     * {@code model} 关联档案表取值，列可作缓存保留或作废。
     *
     * <p>为 {@code null} 时不可判（不回落到算法层统一上限）——由候选链按「缺失即排除」处理，
     * 见 {@code FeasibilityJudge}。
     */
    private BigDecimal windLimitMs;

    /**
     * 机型巡航速度（m/s）——机型能力包线「巡航速度」的定量落点（62 功能 3 输入
     * 「巡航/最大速度」）。
     *
     * <p>此前会面链只能用 {@code sys_param.droneCruiseSpeedMs} 这一全局兜底值
     * （见 {@code DroneContextAdapter} 的注释「机型档案落地后应改为按 model 取档案速度」），
     * 导致「不同机型有效速度不同」无法成立。本列落地后，机型间速度差可进会面求解。
     *
     * <p>口径同 {@link #windLimitMs}：按机型冗余在实例行，{@code null} 时由调用方回落全局兜底值。
     */
    private BigDecimal cruiseSpeedMs;

    /** 当前电量（百分比 0–100） */
    private BigDecimal battery;

    /** 健康状态：健康/告警 */
    private String healthStatus;

    /** 状态（23 §7） */
    private String status;
}
