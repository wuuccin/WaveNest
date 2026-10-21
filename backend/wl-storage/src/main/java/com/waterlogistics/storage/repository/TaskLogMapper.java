package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.TaskLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskLogMapper extends BaseMapper<TaskLogEntity> {
}
