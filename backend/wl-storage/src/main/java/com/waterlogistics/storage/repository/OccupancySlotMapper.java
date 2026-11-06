package com.waterlogistics.storage.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OccupancySlotMapper extends BaseMapper<OccupancySlotEntity> {

    /**
     * MySQL 会话级命名锁（HIGH.33）：把 occupy 的「查重 → 插入」串行化到数据库层，
     * 跨进程/多实例同样成立，无需改表结构。须与后续插入在同一事务（同一连接）内调用。
     *
     * @param name       锁名（≤64 字符，超长由调用方截断+散列）
     * @param timeoutSec 等锁超时秒数
     * @return 1=拿到锁；0=超时；null=出错（锁名非法等）
     */
    @Select("SELECT GET_LOCK(#{name}, #{timeoutSec})")
    Integer getLock(@Param("name") String name, @Param("timeoutSec") int timeoutSec);

    /** 释放命名锁；返回 1=已释放，0=锁不属于本会话，null=锁不存在。 */
    @Select("SELECT RELEASE_LOCK(#{name})")
    Integer releaseLock(@Param("name") String name);
}
