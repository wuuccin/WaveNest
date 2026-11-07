package com.waterlogistics.dispatch.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import org.springframework.stereotype.Service;

/** 货物的物理状态写入；每次仅允许从指定前态迁移，避免交付与回收并发双写。 */
@Service
public class GoodsLifecycleService {
    private final GoodsMapper goodsMapper;

    public GoodsLifecycleService(GoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    /** 起飞前将已就绪货物绑定到本机。 */
    public void load(String orderId, String droneId) {
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalStateException("装机缺少执行机：" + orderId);
        }
        int rows = goodsMapper.update(null, Wrappers.<GoodsEntity>lambdaUpdate()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getStatus, GoodsStatus.READY.getValue())
                .set(GoodsEntity::getStatus, GoodsStatus.LOADED.getValue())
                .set(GoodsEntity::getDroneId, droneId)
                .set(GoodsEntity::getLocation, "空中"));
        requireOne(rows, orderId, "已就绪→已装机");
    }

    /** 船端交接成功后，货物不再属于无人机。 */
    public void deliver(String orderId, String droneId) {
        int rows = goodsMapper.update(null, Wrappers.<GoodsEntity>lambdaUpdate()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getStatus, GoodsStatus.LOADED.getValue())
                .eq(GoodsEntity::getDroneId, droneId)
                .set(GoodsEntity::getStatus, GoodsStatus.DELIVERED.getValue())
                .set(GoodsEntity::getDroneId, null)
                .set(GoodsEntity::getLocation, "目标船"));
        requireOne(rows, orderId, "已装机→已交付");
    }

    /** 仅在原机已落巢且货物回收已确认时调用；野外迫降不得调用。 */
    public void recycle(String orderId, String goodsId, String droneId, String nestId) {
        if (nestId == null || nestId.isBlank()) {
            throw new IllegalStateException("回收缺少落巢标识：" + orderId);
        }
        int rows = goodsMapper.update(null, Wrappers.<GoodsEntity>lambdaUpdate()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getGoodsId, goodsId)
                .eq(GoodsEntity::getStatus, GoodsStatus.LOADED.getValue())
                .eq(GoodsEntity::getDroneId, droneId)
                .set(GoodsEntity::getStatus, GoodsStatus.RECYCLED.getValue())
                .set(GoodsEntity::getDroneId, null)
                .set(GoodsEntity::getLocation, nestId));
        requireOne(rows, orderId, "已装机→已回收");
    }

    /**
     * 是否已有真实交付凭证（货物状态为「已交付」）。
     *
     * <p><b>D-02／D-14</b>：交付凭证只能由真实投放回执写入，时间兜底不写。
     * 队列侧用它做回执幂等判定，飞行阶段推进器用它决定「返航中 → 已完成」能否发生。
     */
    public boolean isDelivered(String orderId) {
        Long count = goodsMapper.selectCount(new LambdaQueryWrapper<GoodsEntity>()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getStatus, GoodsStatus.DELIVERED.getValue()));
        return count != null && count > 0;
    }

    /** 落巢回收确认时的前态校验：货物须仍为「已装机」，重复确认不得二次写入。 */
    public boolean isLoaded(String orderId, String goodsId, String droneId) {
        return countStatus(orderId, goodsId, droneId, GoodsStatus.LOADED) > 0;
    }

    /** 是否已确认回收（重复确认判定）。 */
    public boolean isRecycled(String orderId, String goodsId) {
        return countStatus(orderId, goodsId, null, GoodsStatus.RECYCLED) > 0;
    }

    private long countStatus(String orderId, String goodsId, String droneId, GoodsStatus status) {
        LambdaQueryWrapper<GoodsEntity> query = new LambdaQueryWrapper<GoodsEntity>()
                .eq(GoodsEntity::getOrderId, orderId)
                .eq(GoodsEntity::getGoodsId, goodsId)
                .eq(GoodsEntity::getStatus, status.getValue());
        if (droneId != null) {
            query.eq(GoodsEntity::getDroneId, droneId);
        }
        Long count = goodsMapper.selectCount(query);
        return count == null ? 0L : count;
    }

    private static void requireOne(int rows, String orderId, String transition) {
        if (rows != 1) {
            throw new IllegalStateException("货物状态冲突或货物行缺失：" + orderId + " " + transition
                    + "，更新行数=" + rows);
        }
    }
}
