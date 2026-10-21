package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 机巢表 nest（37 §5.1）
 * 状态枚举值见 23 §10：可用 / 维护中
 */
@Data
@TableName("nest")
public class NestEntity {

    /** 机巢标识 */
    @TableId(type = IdType.INPUT)
    private String nestId;

    /** 经度（CGCS2000/WGS84） */
    private BigDecimal lon;

    /** 纬度（CGCS2000/WGS84） */
    private BigDecimal lat;

    /** 容量 C：同一时段可容纳无人机总数（nest 表为权威，sys_param nestCapacity 初始化源） */
    private Integer capacity;

    /** 支持机型列表 */
    private String supportedModels;

    /** 归属：运营方/性质 */
    private String owner;

    /** 状态：可用/维护中 */
    private String status;
}
