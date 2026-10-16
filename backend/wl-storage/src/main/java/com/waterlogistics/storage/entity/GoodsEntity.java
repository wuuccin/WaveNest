package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 货物表 goods（37 §5.3）
 * 状态枚举值见 23 §6：未就绪/已就绪/已装机/已交付/已回收/灭失
 */
@Data
@TableName("goods")
public class GoodsEntity {

    /** 货物标识 */
    @TableId(type = IdType.INPUT)
    private String goodsId;

    /** 关联订单 */
    private String orderId;

    /** 重量（kg） */
    private BigDecimal weight;

    /** 体积 */
    private BigDecimal volume;

    /** 货物类型 */
    private String type;

    /** 状态（23 §6） */
    private String status;

    /** 当前载体（装机后的承载无人机） */
    private String droneId;

    /** 当前位置：所在巢/迫降点/空中 */
    private String location;
}
