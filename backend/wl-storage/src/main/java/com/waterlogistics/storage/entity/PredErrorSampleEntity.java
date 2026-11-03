package com.waterlogistics.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预测误差样本表 pred_error_sample（11-02 阶段二 5 · 回流校准）。
 *
 * <p>一行 = 一次「某版本预测 在某 τ 处 与 实测点」的配对残差。字段口径与
 * {@code 80-A组算法方案} §5.1 的 NEES 公式对应：{@code (errAlong/σ∥)² + (errCross/σ⊥)²}，
 * 故除清单要求的七项外另存两条 σ——不存就得为算 NEES 回算法层重推当时那版协方差，
 * 而快照不落库、那一版已不可复现。
 *
 * <p>符号约定（与 {@code PredictionCalibrationService} 同步，改动须两处一起改）：
 * {@code errAlongM > 0} 实测在预测位置之前；{@code errCrossM > 0} 实测偏航道右侧；
 * {@code errCourseDeg ∈ [-180,180]} 取最短弧。
 *
 * <p><b>不写 createTime</b>：该列由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 填。
 * MyBatis-Plus 默认插入策略为 {@code NOT_NULL}，null 字段不进 INSERT 语句，
 * 数据库默认值才有机会生效；若在此赋 {@code LocalDateTime.now()} 反而绕过了库时钟。
 */
@Data
@TableName("pred_error_sample")
public class PredErrorSampleEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 船舶 MMSI */
    private String shipMmsi;

    /** 产生该预测的快照版本号 */
    private Long predVersion;

    /** 预测时域 τ，s */
    private Integer tauSec;

    /** 纵向误差，m；>0 实测在预测位置之前 */
    private Double errAlongM;

    /** 横向误差，m；>0 实测偏航道右侧 */
    private Double errCrossM;

    /** 航向误差，度，最短弧 [-180,180]；无有效实测航向时为 null */
    private Double errCourseDeg;

    /** 纵向 σ∥，m（标准差非方差） */
    private Double sigmaAlongM;

    /** 横向 σ⊥，m（标准差非方差） */
    private Double sigmaCrossM;

    /**
     * 该拍后验纵向速度估计，m/s（带符号）。
     *
     * <p>11-03 D2 补：偏差校准器按「时域 × 船速」分桶，船速是分桶键的一部分。
     * 只在内存里带一下、不落库，离线复标就无法重建分桶——同一批样本在库里
     * 只能重算出全局结论，重算不出分桶建议。
     */
    private Double vAlongMs;

    /** 样本时刻（实测点时刻，毫秒精度） */
    private LocalDateTime sampleTs;

    /** 入库时刻（库侧默认值填充，应用不写） */
    private LocalDateTime createTime;
}
