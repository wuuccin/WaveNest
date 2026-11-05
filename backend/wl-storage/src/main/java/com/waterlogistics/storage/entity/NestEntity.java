package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 机巢表 nest（37 §5.1）
 * 状态枚举值见 23 §10：可用 / 维护中
 */
@Data
@TableName("nest")
public class NestEntity {

    /** 机巢标识 */
    @TableId(type = IdType.INPUT)
    private String nestId;

    /** 经度（CGCS2000/WGS84） */
    private BigDecimal lon;

    /** 纬度（CGCS2000/WGS84） */
    private BigDecimal lat;

    /** 容量 C：同一时段可容纳无人机总数（nest 表为权威，sys_param nestCapacity 初始化源） */
    private Integer capacity;

    /** 支持机型列表 */
    private String supportedModels;

    /** 归属：运营方/性质 */
    private String owner;

    /** 状态：可用/维护中 */
    private String status;

    /**
     * 当前在巢占用数（<b>非表列</b>，11-05 新增）。
     *
     * <p>由候选查询的 SQL 侧算好塞进来（{@code CandidateQueries.availableNestsWithOccupancy}），
     * 用 {@code @TableField(exist = false)} 标明不对应表列——{@code nest} 表按 001_init.sql
     * 没有这一列，本批<b>禁改表结构</b>，故占用数只能按 drone 台账现算。
     *
     * <p>取值约定：{@code null}／负数＝<b>未知</b>；未知时容量闸不拦
     * （见 {@code CandidateInput.NestView#hasFreeSlotFor()}）。
     */
    @TableField(exist = false)
    private Integer occupiedCount;
}
