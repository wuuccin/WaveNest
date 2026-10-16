package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderTaskMapper extends BaseMapper<OrderTaskEntity> {
}
