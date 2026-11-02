package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.PredErrorSampleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预测误差样本表 Mapper（pred_error_sample）。
 *
 * <p>只用 {@link BaseMapper} 的通用能力：写入走 {@code insert}，离线复标按
 * {@code ship_mmsi + sample_ts} 区间查走 {@code selectList(Wrapper)}。
 * 没有自定义 SQL——聚合统计（NEES 均值、分桶）属算法层职责，
 * 放 SQL 里会形成第二套口径（《包结构设计》§二：算法只在 algo 层）。
 */
@Mapper
public interface PredErrorSampleMapper extends BaseMapper<PredErrorSampleEntity> {
}
