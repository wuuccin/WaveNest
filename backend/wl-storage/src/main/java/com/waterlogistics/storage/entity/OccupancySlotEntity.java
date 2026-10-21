package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 占位时隙台账 occupancy_slot（37 §5.5）
 * 状态枚举值见 23 §10：有效/已释放/已过期
 * 对象类型：机占用/起飞时段/会面窗口/归巢时隙/迫停占用
 */
@Data
@TableName("occupancy_slot")
public class OccupancySlotEntity {

    /** 占用标识 */
    @TableId(type = IdType.INPUT)
    private String slotId;

    /** 对象类型：机占用/起飞时段/会面窗口/归巢时隙/迫停占用 */
    private String objectType;

    /** 关联对象 Id：droneId/nestId/orderId/迫降点Id */
    private String objectId;

    /** 占用开始时刻 t₁ */
    private LocalDateTime t1;

    /** 占用结束时刻 t₂（半开区间 [t1,t2)） */
    private LocalDateTime t2;

    /** 类型：常规驻留/应急迫停/迫降 */
    private String type;

    /** 状态：有效/已释放/已过期 */
    private String status;
}
