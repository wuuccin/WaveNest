package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计划航迹点表 plan_track（11-12 序号 2；建表脚本 {@code deploy/sql/017_plan_track.sql}）。
 *
 * <p>字段口径以《1112-功能做实批次｜接口约定》v1.2 §2 为唯一来源，本类只做映射副本，
 * 改字段须先改约定再改此表与实体。只记<b>计划</b>点，实际飞行点仍写
 * {@link FlightPointEntity}（{@code flight_point}），两表不互相替代。
 *
 * <p>一行 = 某订单某一航迹版本（{@code route_id}）下某一段（{@code segment_kind}）的第
 * {@code seq} 个计划点；唯一键 {@code uk_plan_point} 含 {@code route_id}，故重规划换号后
 * 旧版本行可与新版本并存，供复盘。
 *
 * <p>{@code segmentKind} 取 {@code PlanTrackFrame} 的中文分段口径词原值
 * （{@code 空机转场}／{@code 去程}／{@code 末段交接}／{@code 返程}），不翻译、不缩写。
 *
 * <p><b>不写 createdAt</b>：该列由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP} 填充；
 * MyBatis-Plus 默认插入策略为 {@code NOT_NULL}，null 字段不进 INSERT，库默认值才会生效。
 */
@Data
@TableName("plan_track")
public class PlanTrackEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号，按订单查回计划航迹的检索键 */
    private String orderId;

    /** 航迹编号，与 B_FlyableRoute.routeId 同源；重规划换号，旧号保留供复盘 */
    private String routeId;

    /** 组合编号，未组批为 null */
    private String comboId;

    /** 分段口径词：空机转场／去程／末段交接／返程，中文原值 */
    private String segmentKind;

    /** 段内点序，自 1 起 */
    private Integer seq;

    /** 经度，十进制度，CGCS2000／WGS-84 */
    private BigDecimal lon;

    /** 纬度，十进制度，CGCS2000／WGS-84 */
    private BigDecimal lat;

    /** 高度，米，可空 */
    private BigDecimal altM;

    /** 速度，米每秒，可空 */
    private BigDecimal speedMs;

    /** 到达该点时刻，Unix 毫秒 */
    private Long tMs;

    /** 写入时刻，库侧默认值填充，应用不写 */
    private LocalDateTime createdAt;
}
