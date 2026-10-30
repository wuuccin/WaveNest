package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警表 alert（37 §5.12）
 * M4 产生、M5 展示；四类告警（偏航/低电量/失联/气象超限）统一由 M4 检测（U-02）
 * 处理状态枚举值见 23 §10：未处理/已确认/已处置/已忽略
 */
@Data
@TableName("alert")
public class AlertEntity {

    /** 告警标识 */
    @TableId(type = IdType.INPUT)
    private String alertId;

    /** 告警对象（无人机） */
    private String droneId;

    /** 告警对象（订单） */
    private String orderId;

    /** 告警类型：失联/低电量/偏航/气象超限/链路异常 */
    private String alertType;

    /** 级别：提示/警告/严重 */
    private String level;

    /** 触发时刻 */
    private LocalDateTime triggerTime;

    /** 处理状态：未处理/已确认/已处置/已忽略 */
    private String handleStatus;

    /** 处置记录：处理人、动作、时间 */
    private String handleRecord;

    /** 告警目标位置-经度（失联取最后一次真实上报；无则 null） */
    private Double lon;

    /** 告警目标位置-纬度 */
    private Double lat;

    /** 当前检测值（偏航距离 m / 电量 % / 风速 m/s；失联时为 null） */
    private Double currentValue;

    /** 阈值（与 currentValue 同量纲） */
    private Double thresholdValue;
}
