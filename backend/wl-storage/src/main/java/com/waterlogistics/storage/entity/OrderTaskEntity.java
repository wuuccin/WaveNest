package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单表 order_task（37 §5.4）
 * 订单状态枚举值见 23 §3（12 态）：待受理/已受理待唤醒/待调度/调度中/已派发/去程中/末段交接中/返航中/已完成/已拒绝/失败/异常终止
 * 优先级（订单级）：NORMAL/URGENT/EMERGENCY（C-09）
 */
@Data
@TableName("order_task")
public class OrderTaskEntity {

    /** 订单标识 */
    @TableId(type = IdType.INPUT)
    private String orderId;

    /** 目标船 MMSI */
    private String shipMmsi;

    /** 目标船舶经度（下单时） */
    private BigDecimal shipLon;

    /** 目标船舶纬度（下单时） */
    private BigDecimal shipLat;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 物资载重（kg） */
    private BigDecimal goodsWeight;

    /** 物资类型 */
    private String goodsType;

    /** 期望送达时间 */
    private LocalDateTime expectedTime;

    /** 优先级（订单级）：NORMAL/URGENT/EMERGENCY */
    private String priority;

    /** 订单状态（23 §3） */
    private String status;

    /** 关联无人机 */
    private String droneId;

    /** 会面点经度 */
    private BigDecimal meetLon;

    /** 会面点纬度 */
    private BigDecimal meetLat;

    /** 组合会面起飞时间（回填） */
    private LocalDateTime meetTime;

    /** 失败/终止原因 */
    private String failReason;
}
