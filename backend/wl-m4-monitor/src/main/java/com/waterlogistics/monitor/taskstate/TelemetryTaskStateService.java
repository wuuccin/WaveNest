package com.waterlogistics.monitor.taskstate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.state.TaskStatus;
import com.waterlogistics.monitor.track.FlightContextProvider;
import com.waterlogistics.monitor.track.FlightTracker;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connects cleaned flight telemetry and genuine handover confirmations to task state. */
@Service
public class TelemetryTaskStateService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryTaskStateService.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private final OrderTaskMapper orders;
    private final TaskLogMapper logs;
    private final GoodsMapper goods;
    private final DroneMapper drones;
    private final ApplicationEventPublisher events;
    private final Map<String, FlightProgress> latestProgress = new ConcurrentHashMap<>();

    public record FlightProgress(String orderId, com.waterlogistics.algo.measurement.RouteMeasurement.Progress progress,
                                 ProgressEta eta, long observedAtMs) { }

    public TelemetryTaskStateService(OrderTaskMapper orders, TaskLogMapper logs,
                                     GoodsMapper goods, DroneMapper drones,
                                     ApplicationEventPublisher events) {
        this.orders = orders;
        this.logs = logs;
        this.goods = goods;
        this.drones = drones;
        this.events = events;
    }

    /** Compute progress and ETA from the cleaned position for the active outbound route. */
    @Transactional
    public ProgressEta onTelemetry(TelemetryState state, FlightContextProvider.FlightContext context,
                                   FlightTracker.Measurement measurement) {
        if (state == null || context == null || context.orderId() == null || measurement == null) return null;
        OrderTaskEntity order = orders.selectById(context.orderId());
        if (order == null || !state.flightId().equals(order.getDroneId())) return null;
        TaskStatus current = taskStatus(order.getStatus());
        if (current == null || TaskStateMachine.isTerminal(current)) return null;

        if (current == TaskStatus.DISPATCHED && state.geometryUsable()) {
            transition(order, TaskStatus.DISPATCHED, TaskStatus.OUTBOUND, state.timestamp(), "遥测确认起飞");
            markCargoLoaded(order);
            current = TaskStatus.OUTBOUND;
        }
        if (current != TaskStatus.OUTBOUND) return null;

        // Zero is the physical division boundary, not a configurable alert threshold.
        double speedFloor = 0.0;
        Double speed = state.groundSpeed();
        ProgressEta eta = ProgressEta.estimate(measurement.remaining(),
                speed == null ? Double.NaN : speed, speedFloor, state.timestamp());
        latestProgress.put(order.getOrderId(), new FlightProgress(
                order.getOrderId(), measurement.progress(), eta, state.timestamp()));
        if (eta.computable()) {
            LocalDateTime etaTime = at(eta.etaMs());
            orders.update(null, new UpdateWrapper<OrderTaskEntity>()
                    .eq("order_id", order.getOrderId())
                    .eq("status", TaskStatus.OUTBOUND.getValue())
                    .set("eta_ts", etaTime));
        }
        return eta;
    }

    /**
     * 真实遥测确认起飞时，把仍在货巢待装的货物绑定到执行机。
     * M2 的时间推进器也执行同一幂等更新；这里补齐 M4 直接确认起飞的主链，
     * 使后续的落巢回收与投放回执拥有合法的货物前态。
     */
    private void markCargoLoaded(OrderTaskEntity order) {
        goods.update(null, new UpdateWrapper<GoodsEntity>()
                .eq("order_id", order.getOrderId())
                .eq("status", GoodsStatus.READY.getValue())
                .set("status", GoodsStatus.LOADED.getValue())
                .set("drone_id", order.getDroneId())
                .set("location", "空中"));
    }

    public FlightProgress latestProgress(String orderId) {
        return latestProgress.get(orderId);
    }

    /** Apply a real algorithm confirmation after M2 delivers it to M4. */
    @Transactional
    public DeliveryConfirm.Result onHandover(String orderId, B_HandoverConfirmation confirmation) {
        if (orderId == null || orderId.isBlank() || confirmation == null) {
            throw new IllegalArgumentException("orderId and confirmation are required");
        }
        OrderTaskEntity order = orders.selectById(orderId);
        if (order == null) throw new IllegalStateException("订单不存在：" + orderId);
        if (!confirmation.droneId().equals(order.getDroneId())) {
            throw new IllegalStateException("交接确认的无人机与订单不一致");
        }
        List<GoodsEntity> rows = goods.selectList(new QueryWrapper<GoodsEntity>()
                .eq("order_id", orderId));
        if (rows == null || rows.size() != 1) throw new IllegalStateException("订单必须对应一件货物");
        GoodsEntity cargo = rows.get(0);
        GoodsStatus cargoStatus = GoodsStatus.fromValue(cargo.getStatus());
        if (TaskStatus.RETURNING.getValue().equals(order.getStatus())
                && cargoStatus == GoodsStatus.DELIVERED) return null;
        if (!TaskStatus.HANDOVER.getValue().equals(order.getStatus())) {
            throw new IllegalStateException("订单尚未进入末段交接中");
        }
        DeliveryConfirm.Result result = DeliveryConfirm.confirm(confirmation, cargoStatus);
        if (!result.confirmed()) return result;
        transition(order, TaskStatus.HANDOVER, result.taskStatus(), result.handoverDoneT(), "交接完成确认");
        int updated = goods.update(null, new UpdateWrapper<GoodsEntity>()
                .eq("goods_id", cargo.getGoodsId())
                .eq("status", GoodsStatus.LOADED.getValue())
                .set("status", result.goodsStatus().getValue()));
        if (updated != 1) throw new IllegalStateException("货物状态并发变更，交接确认已回滚");
        return result;
    }

    /**
     * 订单状态推进：改 {@code order_task.status} ＋ 写 {@code task_log} ＋ 同步无人机态 ＋ 广播订单视图。
     *
     * <p><b>为什么这四件事必须在一起</b>：只改库不广播，界面就停在推进前的状态——库里已是
     * 「去程中」而列表仍显示「已派发」，演示现场无法解释；只改库不同步无人机态，机位会被
     * 一直占在「已预占」，下一单少一架可用机。无人机态回写口径与
     * {@code FlightPhaseAdvancer.syncDroneStatus} 一致；两个模块互不依赖（M4 只依赖
     * {@code wl-contract}／{@code wl-algo}／{@code wl-storage}），故此处按同一口径实现一份，
     * 不抽公共件以免为一行更新改动模块依赖图。
     */
    private void transition(OrderTaskEntity order, TaskStatus from, TaskStatus to, long timestamp, String operation) {
        TaskStateMachine.requireTransit(from, to);
        int updated = orders.update(null, new UpdateWrapper<OrderTaskEntity>()
                .eq("order_id", order.getOrderId())
                .eq("status", from.getValue())
                .set("status", to.getValue()));
        if (updated != 1) throw new IllegalStateException("订单状态并发变更，任务流转已回滚");
        TaskLogEntity log = new TaskLogEntity();
        log.setOrderId(order.getOrderId());
        log.setOpType(operation);
        log.setFromStatus(from.getValue());
        log.setToStatus(to.getValue());
        log.setTs(at(timestamp));
        logs.insert(log);

        syncDroneStatus(order.getDroneId(), to);
        publishOrderState(order, to, timestamp, log.getLogId());
    }

    /**
     * 广播已确认的订单视图。
     *
     * <p>版本号取 {@code task_log.log_id + 1}，与 {@code FlightPhaseAdvancer} 同口径：前端按
     * {@code stateVersion} 单调递增丢弃乱序帧，版本不动等于帧被丢掉。
     * {@code log_id} 为空（未落库的桩场景）时不发——没有版本号就没有合并语义。
     */
    private void publishOrderState(OrderTaskEntity order, TaskStatus to, long timestamp, Long logId) {
        if (events == null || logId == null) {
            return;
        }
        OrderStatus next = orderStatus(to);
        if (next == null) {
            return;
        }
        Long etaMs = order.getEtaTs() == null
                ? null
                : order.getEtaTs().atZone(DB_ZONE).toInstant().toEpochMilli();
        try {
            // 配送品类与货巢同为建单时刻确定的字段：本帧不带，前端整对象替换后会把订单上的品类抹成「未记录」。
            events.publishEvent(new OrderStateChangedEvent(new OrderView(
                    order.getOrderId(), next, timestamp, Math.max(2L, logId + 1L),
                    null, null, null, null, etaMs, order.getCargoNestId(), order.getGoodsType())));
        } catch (Exception e) {
            // 审计与状态已落库，推送失败只告警：下一拍不会重推同一阶段，避免重复刷屏
            log.warn("订单状态广播失败（task_log 已落库）：orderId={} status={} err={}",
                    order.getOrderId(), to.getValue(), e.getMessage());
        }
    }

    /**
     * 无人机态回写：起飞置「执行任务中／空中」，返航置「返航中／空中」。
     *
     * <p>条件更新：只接「本单该接的」机，已被别的单占住或在飞的键不覆盖。
     */
    private void syncDroneStatus(String droneId, TaskStatus to) {
        if (drones == null || droneId == null) {
            return;
        }
        LambdaUpdateWrapper<DroneEntity> update = new LambdaUpdateWrapper<DroneEntity>()
                .eq(DroneEntity::getDroneId, droneId);
        switch (to) {
            case OUTBOUND -> update
                    .in(DroneEntity::getStatus, "空闲", "已预占")
                    .set(DroneEntity::getStatus, "执行任务中")
                    .set(DroneEntity::getPositionType, "空中")
                    .set(DroneEntity::getCurrentNestId, null);
            case RETURNING -> update
                    .eq(DroneEntity::getStatus, "执行任务中")
                    .set(DroneEntity::getStatus, "返航中")
                    .set(DroneEntity::getPositionType, "空中");
            default -> {
                return;
            }
        }
        int affected = drones.update(null, update);
        if (affected == 0) {
            log.warn("无人机状态未更新（当前状态不在本单可接范围）：droneId={} 目标状态={}",
                    droneId, to.getValue());
        }
    }

    /** 任务态转订单态；{@code 迫停中}／{@code 迫降待回收} 不在订单态取值内，返回 {@code null}。 */
    private static OrderStatus orderStatus(TaskStatus value) {
        try {
            return OrderStatus.fromValue(value.getValue());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static TaskStatus taskStatus(String value) {
        try {
            return TaskStatus.fromValue(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static LocalDateTime at(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), DB_ZONE);
    }
}
