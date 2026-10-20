package com.waterlogistics.dispatch.queue;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderSubmittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主链触发器（B0 · 10-20 任务清单）。
 *
 * <p>下单 → 受理 → 组批 → 候选 → 会面 → 派发 的<b>自动推进器</b>：
 * 监听 {@link OrderSubmittedEvent}，把内存中的订单落到 {@code order_task}，
 * 再驱动 {@link TaskQueueService#accept}（待受理 → 待调度）→
 * {@link TaskQueueService#startDispatch}（待调度 → 调度中），
 * 使订单不再卡在「待受理」。
 *
 * <p><b>闸门联动（B3）</b>：{@code startDispatch} 内部已查 {@code DispatchGateService}，
 * 熔断时自动转「已受理待唤醒」，不在此处重复判定。
 *
 * <p>本类<b>不处理</b>候选计算、会面求解、派发（droneId 回填）——那些由后续
 * 调度链在「调度中」状态下异步完成，本触发器只负责把订单推进到「调度中」入口。
 */
@Component
public class DispatchTrigger {

    private static final Logger log = LoggerFactory.getLogger(DispatchTrigger.class);

    private final OrderTaskMapper orderTaskMapper;
    private final TaskQueueService taskQueueService;

    public DispatchTrigger(OrderTaskMapper orderTaskMapper, TaskQueueService taskQueueService) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskQueueService = taskQueueService;
    }

    /**
     * 订单受理事件处理：落库 → 受理 → 组批调度。
     *
     * <p>整链路在同一事务内：任一步失败则回滚，订单不产生脏状态。
     * 事件发布方（OrderAdmissionService）不依赖本监听器的返回值，
     * 失败仅记录日志，不阻断下单响应（下单已返回 accepted）。
     */
    @EventListener
    @Transactional
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        String orderId = event.orderId();
        try {
            // 1. 落库 order_task（待受理）——OrderAdmissionService 只存内存，落库由 queue 负责
            OrderTaskEntity entity = new OrderTaskEntity();
            entity.setOrderId(orderId);
            entity.setShipMmsi(event.shipMmsi());
            entity.setGoodsWeight(event.goodsWeightDecimal());
            entity.setGoodsType(event.goodsType());
            if (event.expectedTimeMs() != null) {
                entity.setExpectedTime(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.expectedTimeMs()), ZoneId.systemDefault()));
            }
            entity.setPriority(event.priority());
            entity.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.submitTsMs()), ZoneId.systemDefault()));
            entity.setStatus(OrderStatus.PENDING_ACCEPT.getValue());
            orderTaskMapper.insert(entity);

            // 2. 受理：待受理 → 待调度
            taskQueueService.accept(entity);

            // 3. 组批调度：待调度 → 调度中（B3 闸门熔断时自动转已受理待唤醒）
            taskQueueService.startDispatch(orderId);

            log.info("主链触发完成：orderId={} 已推进至「调度中」", orderId);
        } catch (Exception e) {
            log.error("主链触发失败：orderId={} err={}", orderId, e.getMessage(), e);
            throw e; // 事务回滚
        }
    }
}
