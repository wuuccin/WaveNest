package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.DroneEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DroneMapper extends BaseMapper<DroneEntity> {
}
