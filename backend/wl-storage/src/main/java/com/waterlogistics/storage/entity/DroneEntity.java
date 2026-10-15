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

    /** 当前电量（百分比 0–100） */
    private BigDecimal battery;

    /** 健康状态：健康/告警 */
    private String healthStatus;

    /** 状态（23 §7） */
    private String status;
}
