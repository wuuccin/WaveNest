package com.waterlogistics.dispatch.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 资源占用与并发冲突（模块二，10-16 简化版）。
 *
 * <p>对 occupancy_slot 做占位时隙登记（37 §5.5），支持对象类型：
 * 机占用／起飞时段／会面窗口／归巢时隙／迫停占用。冲突判定采用半开区间
 * [t1, t2) 相交检测（83 §4.2.6 原子占位要求的简化实现）。
 *
 * <p>状态枚举值见 23 §10：有效／已释放／已过期。
 */
@Service
public class OccupancySlotService {

    private static final String STATUS_ACTIVE = "有效";
    private static final String STATUS_RELEASED = "已释放";

    private final OccupancySlotMapper occupancySlotMapper;

    public OccupancySlotService(OccupancySlotMapper occupancySlotMapper) {
        this.occupancySlotMapper = occupancySlotMapper;
    }

    /**
     * 登记占位时隙。若同一对象在 [t1, t2) 已有有效占位则抛冲突异常。
     *
     * @param objectType 对象类型（机占用/起飞时段/会面窗口/归巢时隙/迫停占用）
     * @param objectId   关联对象 Id
     * @param t1         占用开始
     * @param t2         占用结束（半开）
     * @param type       类型：常规驻留/应急迫停/迫降
     * @return 新登记的 slotId
     */
    @Transactional
    public String occupy(String objectType, String objectId,
                         LocalDateTime t1, LocalDateTime t2, String type) {
        if (hasConflict(objectType, objectId, t1, t2)) {
            throw new IllegalStateException(
                "对象 " + objectType + "/" + objectId + " 在 [" + t1 + ", " + t2 + ") 已有有效占位");
        }
        OccupancySlotEntity slot = new OccupancySlotEntity();
        slot.setSlotId("SLOT-" + UUID.randomUUID().toString().substring(0, 8));
        slot.setObjectType(objectType);
        slot.setObjectId(objectId);
        slot.setT1(t1);
        slot.setT2(t2);
        slot.setType(type);
        slot.setStatus(STATUS_ACTIVE);
        occupancySlotMapper.insert(slot);
        return slot.getSlotId();
    }

    /** 释放占位（状态 → 已释放） */
    @Transactional
    public void release(String slotId) {
        OccupancySlotEntity slot = occupancySlotMapper.selectById(slotId);
        if (slot != null) {
            slot.setStatus(STATUS_RELEASED);
            occupancySlotMapper.updateById(slot);
        }
    }

    /** 按对象类型+Id 查询当前有效占位列表 */
    public List<OccupancySlotEntity> activeSlots(String objectType, String objectId) {
        return occupancySlotMapper.selectList(
            new LambdaQueryWrapper<OccupancySlotEntity>()
                .eq(OccupancySlotEntity::getObjectType, objectType)
                .eq(OccupancySlotEntity::getObjectId, objectId)
                .eq(OccupancySlotEntity::getStatus, STATUS_ACTIVE));
    }

    /** 半开区间冲突检测：[t1,t2) 与任一有效占位相交 */
    private boolean hasConflict(String objectType, String objectId,
                                LocalDateTime t1, LocalDateTime t2) {
        List<OccupancySlotEntity> existing = activeSlots(objectType, objectId);
        for (OccupancySlotEntity s : existing) {
            // 区间相交：s.t1 < t2 && t1 < s.t2
            if (s.getT1().isBefore(t2) && t1.isBefore(s.getT2())) {
                return true;
            }
        }
        return false;
    }
}
