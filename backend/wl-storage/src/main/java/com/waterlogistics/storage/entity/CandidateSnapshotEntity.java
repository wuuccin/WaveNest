package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 候选快照表 candidate_snapshot（11-19 序号 3-1；建表脚本 {@code deploy/sql/020_candidate_snapshot.sql}）。
 *
 * <p>一行 = 一次派单决策里的一个候选组合，九项业务字段与《11-19 任务清单》§3 逐字对应。
 * 中选者的 {@code rejectReason} 写「中选」，落选者写判定端中文原因；只有中选者带
 * {@code meetTime}（落选者未进入会面求解，保持 NULL，不拿转场 ETA 冒充会面时刻）。
 *
 * <p><b>本表与 {@code task_log} 不混流</b>：候选留痕只进本表，任务日志里不会出现候选行。
 *
 * <p>{@code createdAt} 由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 填充，
 * 应用不写（MyBatis-Plus 默认 NOT_NULL 策略下 null 字段不进 INSERT）。
 */
@Data
@TableName("candidate_snapshot")
public class CandidateSnapshotEntity {

    /** 自增主键；同一订单内同时充当候选生成序 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号，按订单回查的检索键 */
    private String orderId;

    /** 机型（CandidateCombo.type），组合缺失为 null */
    private String model;

    /** 机巢（组合出发巢） */
    private String nestId;

    /** 机号（执行机实例） */
    private String droneId;

    /** 机巢到取货点距离，km；上游为 NaN 时写 null */
    private BigDecimal distKm;

    /** 会面时刻：仅中选候选有值（定案值，含错峰时移） */
    private LocalDateTime meetTime;

    /** 落选原因（判定端中文原文，多条以「；」连接）；中选者写「中选」 */
    private String rejectReason;

    /** 择优键值 "delay=…;dist=…"，与 FeasibleCandidatePicker 两级键同式 */
    private String rankKey;

    /** 快照版本（MeetPlan.snapshotVersion） */
    private String snapshotVersion;

    /** 写入时刻，库侧默认值填充，应用不写 */
    private LocalDateTime createdAt;
}
