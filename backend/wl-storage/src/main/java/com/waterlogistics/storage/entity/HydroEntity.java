package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水文表 hydro（37 §5.8）
 * 主流程暂不消费可降级仅缓存
 */
@Data
@TableName("hydro")
public class HydroEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 观测时刻 */
    private LocalDateTime ts;

    /** 区域/断面标识 */
    private String region;

    /** 经度 */
    private BigDecimal lon;

    /** 纬度 */
    private BigDecimal lat;

    /** 水位（m） */
    private BigDecimal waterLevel;

    /** 流速（m/s） */
    private BigDecimal flowSpeed;

    /** 流向（度） */
    private BigDecimal flowDir;

    /** 水温（℃） */
    private BigDecimal waterTemp;
}
