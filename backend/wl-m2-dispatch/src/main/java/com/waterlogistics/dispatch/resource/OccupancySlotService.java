package com.waterlogistics.dispatch.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源占用与并发冲突（模块二）。
 *
 * <p>对 {@code occupancy_slot} 做占位时隙登记（37 §5.5），支持对象类型：
 * 机占用／起飞时段／会面窗口／归巢时隙／迫停占用。冲突判定采用半开区间
 * {@code [t1, t2)} 相交检测（83 §4.2.6）。
 *
 * <h3>返航三级降级用的归巢占位入口</h3>
 *
 * <p>{@link #tryReserve(String, TimeWindow)} 是功能10 第二级「迫停他巢」的落子入口：
 * 同一事务内先对 {@code nest} 行加 {@code FOR UPDATE} 行锁，再按到达时窗统计已占位数并插入，
 * <b>把原先「先查冲突、后插入」的 check-then-act 收敛成按巢串行的条件插入</b>（HIGH.34）。
 * 行锁是跨进程的，故多实例部署也成立；表结构无需新增唯一键（当日不得改表）。
 *
 * <p>状态枚举值见 23 §10：有效／已释放／已过期。
 */
@Service
public class OccupancySlotService {

    private static final Logger log = LoggerFactory.getLogger(OccupancySlotService.class);

    private static final String STATUS_ACTIVE = "有效";
    private static final String STATUS_RELEASED = "已释放";

    /** 第二级迫停占位写入的对象类型（37 §5.5 取值之一） */
    private static final String OBJECT_TYPE_FORCED_STOP = "迫停占用";

    /** 第二级迫停占位的类型（37 §5.5 取值之一） */
    private static final String TYPE_EMERGENCY_STOP = "应急迫停";

    /** 机巢可用状态（23 §10） */
    private static final String NEST_STATUS_AVAILABLE = "可用";

    /** 机巢容量兜底键与默认值（nest 表为权威，键只在行缺失时兜底） */
    private static final String NEST_CAPACITY_KEY = "nestCapacity";
    private static final int DEFAULT_NEST_CAPACITY = 4;

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final OccupancySlotMapper occupancySlotMapper;
    private final NestMapper nestMapper;
    private final SysParamLoader sysParamLoader;

    public OccupancySlotService(OccupancySlotMapper occupancySlotMapper,
            NestMapper nestMapper, SysParamLoader sysParamLoader) {
        this.occupancySlotMapper = occupancySlotMapper;
        this.nestMapper = nestMapper;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 登记占位时隙。若同一对象在 {@code [t1, t2)} 已有有效占位则抛冲突异常。
     *
     * <p><b>并发口径</b>：本方法在单进程内串行化；跨进程的归巢占位请走
     * {@link #tryReserve(String, TimeWindow)}（对机巢行加锁）。本方法保留给非机巢对象类型。
     *
     * @param objectType 对象类型（机占用/起飞时段/会面窗口/归巢时隙/迫停占用）
     * @param objectId   关联对象 Id
     * @param t1         占用开始
     * @param t2         占用结束（半开）
     * @param type       类型：常规驻留/应急迫停/迫降
     * @return 新登记的 slotId（完整 UUID）
     */
    @Transactional
    public synchronized String occupy(String objectType, String objectId,
                         LocalDateTime t1, LocalDateTime t2, String type) {
        validateSlot(objectType, objectId, t1, t2, type);
        if (countOverlapping(objectType, objectId, t1, t2) > 0) {
            throw new IllegalStateException(
                "对象 " + objectType + "/" + objectId + " 在 [" + t1 + ", " + t2 + ") 已有有效占位");
        }
        return insertSlot(objectType, objectId, t1, t2, type, null);
    }

    /**
     * 尝试为机巢登记一段迫停占位（功能10 第二级「迫停他巢」）。
     *
     * <p>判定顺序：机巢存在且可用 → 到达时窗内已占位数小于容量 → 插入。
     * <b>三步在同一事务内、且以 {@code nest} 行锁串行化</b>，不存在「查时空、插时已被占」的窗口。
     *
     * @param nestId  目标机巢标识
     * @param window  到达时窗（含两端缓冲，半开区间）
     * @param orderId 关联订单（用于终态按订单释放占位），可为空
     * @return 占位成功返回新 slotId；机巢不可用、容量已满或时窗冲突时返回空（由调用方顺延候选）
     * @throws IllegalArgumentException nestId 为空或 window 为空（调用方缺陷）
     */
    @Transactional
    public Optional<String> tryReserve(String nestId, TimeWindow window, String orderId) {
        if (nestId == null || nestId.isBlank()) {
            throw new IllegalArgumentException("tryReserve 须带 nestId，否则无从锁定目标机巢");
        }
        if (window == null) {
            throw new IllegalArgumentException("tryReserve 须带占位时窗，实测 window=null");
        }

        // 先锁机巢行：同一巢的「容量统计 → 插入」在此后串行，跨进程同样生效
        NestEntity nest = nestMapper.selectOne(new LambdaQueryWrapper<NestEntity>()
                .eq(NestEntity::getNestId, nestId)
                .last("FOR UPDATE"));
        if (nest == null) {
            log.warn("迫停占位失败：机巢不存在 nestId={}", nestId);
            return Optional.empty();
        }
        if (nest.getStatus() != null && !NEST_STATUS_AVAILABLE.equals(nest.getStatus())) {
            log.info("迫停占位失败：机巢非可用状态 nestId={} status={}", nestId, nest.getStatus());
            return Optional.empty();
        }

        int capacity = nest.getCapacity() == null
                ? sysParamLoader.getInt(NEST_CAPACITY_KEY, DEFAULT_NEST_CAPACITY)
                : nest.getCapacity();
        if (capacity <= 0) {
            log.info("迫停占位失败：机巢容量为 0 nestId={}", nestId);
            return Optional.empty();
        }

        LocalDateTime start = toLocalDateTime(window.startMs());
        LocalDateTime end = toLocalDateTime(window.endMs());
        long occupied = countOverlapping(null, nestId, start, end);
        if (occupied >= capacity) {
            log.info("迫停占位失败：到达时窗内已占满 nestId={} 已占={} 容量={}", nestId, occupied, capacity);
            return Optional.empty();
        }

        String slotId = insertSlot(OBJECT_TYPE_FORCED_STOP, nestId, start, end, TYPE_EMERGENCY_STOP, orderId);
        log.info("迫停占位成功：nestId={} slotId={} 已占={}/{} 窗口=[{}, {})",
                nestId, slotId, occupied + 1, capacity, start, end);
        return Optional.of(slotId);
    }

    /**
     * 释放占位（状态 → 已释放）。
     *
     * <p><b>不再静默成功</b>（MEDIUM.61）：未知 slotId、非「有效」状态、或条件更新影响 0 行
     * 都视为调用方缺陷并抛出，避免把「没释放到」当成「已释放」。
     *
     * @throws IllegalArgumentException slotId 为空
     * @throws IllegalStateException    占位不存在或当前状态不可释放
     */
    @Transactional
    public void release(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalArgumentException("释放占位须带 slotId");
        }
        OccupancySlotEntity slot = occupancySlotMapper.selectById(slotId);
        if (slot == null) {
            throw new IllegalStateException("未知占位 slotId=" + slotId + "，不可释放");
        }
        if (!STATUS_ACTIVE.equals(slot.getStatus())) {
            throw new IllegalStateException("占位 slotId=" + slotId + " 当前状态为 "
                    + slot.getStatus() + "，仅有效占位可释放");
        }
        int rows = occupancySlotMapper.update(null, new LambdaUpdateWrapper<OccupancySlotEntity>()
                .eq(OccupancySlotEntity::getSlotId, slotId)
                .eq(OccupancySlotEntity::getStatus, STATUS_ACTIVE)
                .set(OccupancySlotEntity::getStatus, STATUS_RELEASED));
        if (rows == 0) {
            throw new IllegalStateException("占位 slotId=" + slotId + " 状态已被并发改动，释放未生效");
        }
    }

    /**
     * 按订单释放其关联的迫停占位（M2 终态释放）。
     *
     * <p>订单到达「已完成」／「失败」终态时调用，避免迫停占位泄漏导致容量统计持续偏高。
     * <b>幂等且不抛异常</b>（C3 释放失败处置）：无对应有效占位时静默返回；
     * 释放过程中 {@link #release(String)} 抛出的状态异常只记日志、不向调用方传播，
     * 订单态不回滚（与 {@code FlightPhaseAdvancer.emit}「审计已落库，推送失败只告警」同口径）。
     *
     * @param orderId 关联订单；为空时直接返回
     */
    @Transactional
    public void releaseByOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        List<OccupancySlotEntity> slots = occupancySlotMapper.selectList(
                new LambdaQueryWrapper<OccupancySlotEntity>()
                        .eq(OccupancySlotEntity::getOrderId, orderId)
                        .eq(OccupancySlotEntity::getObjectType, OBJECT_TYPE_FORCED_STOP)
                        .eq(OccupancySlotEntity::getStatus, STATUS_ACTIVE));
        if (slots.isEmpty()) {
            return;
        }
        for (OccupancySlotEntity slot : slots) {
            try {
                release(slot.getSlotId());
                log.info("订单终态释放迫停占位：orderId={} slotId={}", orderId, slot.getSlotId());
            } catch (IllegalStateException e) {
                log.warn("订单终态释放迫停占位失败（不影响订单态）：orderId={} slotId={} err={}",
                        orderId, slot.getSlotId(), e.getMessage());
            }
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

    /**
     * 查询某对象在给定时窗内的有效占位时窗（供功能10 第二级候选台账装配）。
     *
     * <p><b>时间维度不可省</b>（MEDIUM.62）：只返回与 {@code window} 相交的有效占位，
     * 容量统计与时隙判定都以「到达前后这段真实时间」为准，而不是把该对象的历史占位全算上。
     *
     * @param objectId 关联对象 Id（此处为 nestId）
     * @param window   到达时窗（含两端缓冲）
     * @return 相交的有效占位时窗；无占位时为空列表
     */
    public List<TimeWindow> activeWindows(String objectId, TimeWindow window) {
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("查询占位时窗须带 objectId");
        }
        if (window == null) {
            throw new IllegalArgumentException("查询占位时窗须带时窗，实测 window=null");
        }
        LocalDateTime start = toLocalDateTime(window.startMs());
        LocalDateTime end = toLocalDateTime(window.endMs());
        return occupancySlotMapper.selectList(overlapQuery(null, objectId, start, end)).stream()
                .map(slot -> new TimeWindow(
                        slot.getT1().atZone(DB_ZONE).toInstant().toEpochMilli(),
                        slot.getT2().atZone(DB_ZONE).toInstant().toEpochMilli()))
                .toList();
    }

    /**
     * 半开区间重叠计数：{@code s.t1 < t2 && t1 < s.t2}。
     *
     * @param objectType 为 {@code null} 时不限对象类型（归巢占位按 nestId 统计全部类型）
     */
    private long countOverlapping(String objectType, String objectId,
                                  LocalDateTime t1, LocalDateTime t2) {
        Long count = occupancySlotMapper.selectCount(overlapQuery(objectType, objectId, t1, t2));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<OccupancySlotEntity> overlapQuery(String objectType, String objectId,
                                                                  LocalDateTime t1, LocalDateTime t2) {
        LambdaQueryWrapper<OccupancySlotEntity> query = new LambdaQueryWrapper<OccupancySlotEntity>()
                .eq(OccupancySlotEntity::getStatus, STATUS_ACTIVE)
                .lt(OccupancySlotEntity::getT1, t2)
                .gt(OccupancySlotEntity::getT2, t1)
                .eq(OccupancySlotEntity::getObjectId, objectId);
        if (objectType != null) {
            query.eq(OccupancySlotEntity::getObjectType, objectType);
        }
        return query;
    }

    private String insertSlot(String objectType, String objectId, LocalDateTime t1,
                              LocalDateTime t2, String type, String orderId) {
        OccupancySlotEntity slot = new OccupancySlotEntity();
        // LOW.10：完整 UUID（原截断 8 位只留 32 bit，碰撞概率不可接受）
        slot.setSlotId("SLOT-" + UUID.randomUUID());
        slot.setObjectType(objectType);
        slot.setObjectId(objectId);
        slot.setOrderId(orderId);
        slot.setT1(t1);
        slot.setT2(t2);
        slot.setType(type);
        slot.setStatus(STATUS_ACTIVE);
        occupancySlotMapper.insert(slot);
        return slot.getSlotId();
    }

    /** HIGH.33：参数与区间校验前置，非法入参不落到 SQL。 */
    private static void validateSlot(String objectType, String objectId,
                                     LocalDateTime t1, LocalDateTime t2, String type) {
        if (objectType == null || objectType.isBlank()) {
            throw new IllegalArgumentException("占位须带 objectType");
        }
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("占位须带 objectId");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("占位须带 type");
        }
        if (t1 == null || t2 == null) {
            throw new IllegalArgumentException("占位起止时刻不可为空，实测 t1=" + t1 + "、t2=" + t2);
        }
        if (!t2.isAfter(t1)) {
            throw new IllegalArgumentException("占位须为非空半开区间 [t1, t2)，实测 t1=" + t1 + "、t2=" + t2);
        }
    }

    private static LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), DB_ZONE);
    }
}
