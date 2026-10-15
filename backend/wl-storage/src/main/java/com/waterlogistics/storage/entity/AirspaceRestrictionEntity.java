package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 空域与航道限制表 airspace_restriction（37 §5.9）
 * 状态枚举值见 23 §10：生效/已失效
 */
@Data
@TableName("airspace_restriction")
public class AirspaceRestrictionEntity {

    /** 限制项标识 */
    @TableId(type = IdType.INPUT)
    private String restrictionId;

    /** 类型：禁飞区/限飞区/限高/航道管制 */
    private String type;

    /** 空间范围：几何区域（多边形/圆/线段）或桥梁净空高度 */
    private String scope;

    /** 生效开始（长期有效则为空） */
    private LocalDateTime effectiveStart;

    /** 生效结束（长期有效则为空） */
    private LocalDateTime effectiveEnd;

    /** 来源与原文：公告来源、原文链接 */
    private String source;

    /** 状态：生效/已失效 */
    private String status;
}
