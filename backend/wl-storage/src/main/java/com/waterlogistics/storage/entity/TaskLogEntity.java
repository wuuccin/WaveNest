package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务日志表 task_log（37 §5.13）
 * 记录所有状态流转，用于排查缺陷与仿真实验溯源
 */
@Data
@TableName("task_log")
public class TaskLogEntity {

    /** 日志标识 */
    @TableId(type = IdType.AUTO)
    private Long logId;

    /** 关联订单 */
    private String orderId;

    /** 操作类型 */
    private String opType;

    /** 变更前状态 */
    private String fromStatus;

    /** 变更后状态（覆盖订单/无人机/货物/占位变更） */
    private String toStatus;

    /** 时间戳 */
    private LocalDateTime ts;
}
