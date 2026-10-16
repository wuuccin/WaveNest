package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统参数表 sys_param（37 §5.14）
 * 键名 camelCase，见 37 §5.14.1；分类：阈值/风级/窗口/容量/权重/间隔/上限/TTL/收敛/流程/限流/公平
 */
@Data
@TableName("sys_param")
public class SysParamEntity {

    /** 参数键（camelCase） */
    @TableId(type = IdType.INPUT)
    private String paramKey;

    /** 参数值 */
    private String paramValue;

    /** 分类 */
    private String category;

    /** 说明 */
    private String description;
}
