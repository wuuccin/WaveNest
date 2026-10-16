package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 应急迫降点台账 emergency_landing_point（37 §5.6）
 * 状态枚举值见 23 §10：可用/占用/维护
 */
@Data
@TableName("emergency_landing_point")
public class EmergencyLandingPointEntity {

    /** 迫降点标识 */
    @TableId(type = IdType.INPUT)
    private String elpId;

    /** 经度 */
    private BigDecimal lon;

    /** 纬度 */
    private BigDecimal lat;

    /** 容量：可容纳迫降机数 */
    private Integer capacity;

    /** 状态：可用/占用/维护 */
    private String status;

    /** 适用机型与限制 */
    private String applicableModels;
}
