package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 气象表 weather（37 §5.7）
 * 实时最新放 Redis，历史落 MySQL
 */
@Data
@TableName("weather")
public class WeatherEntity {

    /** 自增主键（时序数据无自然键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 观测时刻 */
    private LocalDateTime ts;

    /** 区域/网格索引 */
    private String region;

    /** 经度 */
    private BigDecimal lon;

    /** 纬度 */
    private BigDecimal lat;

    /** 风速（m/s） */
    private BigDecimal windSpeed;

    /** 风向（度） */
    private BigDecimal windDir;

    /** 阵风（m/s） */
    private BigDecimal gust;

    /** 风级（由风速换算；换算方未定见 53 §2） */
    private Integer windLevel;

    /** 温度（℃） */
    private BigDecimal temperature;

    /** 能见度（m） */
    private BigDecimal visibility;
}
