package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 航迹点表 flight_point（37 §5.11）
 * 只记实际飞行点，供 M4 偏航比对
 */
@Data
@TableName("flight_point")
public class FlightPointEntity {

    /** 航迹点标识 */
    @TableId(type = IdType.INPUT)
    private String pointId;

    /** 关联无人机 */
    private String droneId;

    /** 关联订单 */
    private String orderId;

    /** 采样时刻 */
    private LocalDateTime ts;

    /** 经度（已归一） */
    private BigDecimal lon;

    /** 纬度（已归一） */
    private BigDecimal lat;

    /** 高度（m，已归一） */
    private BigDecimal alt;

    /** 速度（m/s） */
    private BigDecimal speed;

    /** 航向（度） */
    private BigDecimal heading;

    /** 航段类型：去程/返程/迫降 */
    private String segmentType;
}
