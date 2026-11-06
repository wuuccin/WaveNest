package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 航道中心线台账点 centerline_point（11-06 序号 2，折线点表）
 *
 * <p>C-01 缺省做法：一段一档（台账号）、折线按顶点存行；拓扑边表留后续。
 * {@code s_m}（沿程弧长）是<b>自检列</b>——读取器按顶点序列重建几何后重算弧长与本列
 * 比对，不一致视为台账灌错；构造几何本身只用顶点序列，不用本列。
 *
 * <p>坐标基准 CGCS2000／WGS-84，与全库同源，不做 GCJ-02 偏移。
 */
@Data
@TableName("centerline_point")
public class CenterlinePointEntity {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 台账号：一段一档（演示期固定 GANJIANG） */
    private String chartId;

    /** 折线版本号，同台账号换曲线时递增 */
    private Integer version;

    /** 顶点序号，按航道走向从 1 起 */
    private Integer seq;

    /** 沿程弧长，m，自首点起算（自检列） */
    private Double sM;

    /** 经度，度 */
    private Double lon;

    /** 纬度，度 */
    private Double lat;
}
