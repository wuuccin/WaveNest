package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.CandidateSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 候选快照表 {@code candidate_snapshot} 的读写口（11-19 序号 3-1）。
 *
 * <p>写侧一次派单落一批行（编排器同一事务内逐条 insert），读侧按订单号回查。
 * 本层不吞异常、不做业务判断：写失败由调用方决定是否 fail-soft。
 */
@Mapper
public interface CandidateSnapshotMapper extends BaseMapper<CandidateSnapshotEntity> {

    /**
     * 按订单号回查全部候选留痕，按生成序（自增主键）升序。
     *
     * @param orderId 订单号
     * @return 候选行；无记录为空列表，不返回 null
     */
    @Select("SELECT * FROM candidate_snapshot WHERE order_id = #{orderId} ORDER BY id")
    List<CandidateSnapshotEntity> selectByOrderId(@Param("orderId") String orderId);
}
