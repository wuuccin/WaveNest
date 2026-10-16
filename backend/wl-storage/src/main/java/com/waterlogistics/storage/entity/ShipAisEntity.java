package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 船舶 AIS 轨迹表 ship_ais（37 §5.10）
 * 原始 AIS 值，M1 唯一写入方；坐标/单位已归一到 CGCS2000/WGS84 与标准单位
 */
@Data
@TableName("ship_ais")
public class ShipAisEntity {

    /** 自增主键（同一船多条轨迹记录） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 船舶标识 */
    private String shipMmsi;

    /** 时间戳 */
    private LocalDateTime ts;

    /** 经度（已归一） */
    private BigDecimal lon;

    /** 纬度（已归一） */
    private BigDecimal lat;

    /** 航速（已归一 m/s） */
    private BigDecimal sog;

    /** 航向（已归一 度） */
    private BigDecimal cog;
}
