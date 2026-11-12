package com.waterlogistics.monitor.taskstate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.TaskStatus;
import com.waterlogistics.monitor.track.FlightContextProvider;
import com.waterlogistics.monitor.track.FlightTracker;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connects cleaned flight telemetry and genuine handover confirmations to task state. */
@Service
public class TelemetryTaskStateService {
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private final OrderTaskMapper orders;
    private final TaskLogMapper logs;
    private final GoodsMapper goods;
    private final Map<String, FlightProgress> latestProgress = new ConcurrentHashMap<>();

    public record FlightProgress(String orderId, com.waterlogistics.algo.measurement.RouteMeasurement.Progress progress,
                                 ProgressEta eta, long observedAtMs) { }

    public TelemetryTaskStateService(OrderTaskMapper orders, TaskLogMapper logs,
                                     GoodsMapper goods) {
        this.orders = orders;
        this.logs = logs;
        this.goods = goods;
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
