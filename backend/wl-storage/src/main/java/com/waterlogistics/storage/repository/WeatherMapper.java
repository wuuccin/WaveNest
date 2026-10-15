package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.WeatherEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeatherMapper extends BaseMapper<WeatherEntity> {
}
