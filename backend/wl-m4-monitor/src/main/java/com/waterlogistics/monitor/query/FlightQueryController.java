package com.waterlogistics.monitor.query;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.rest.FlightReplayResponse;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.monitor.store.ShipPredictionReplayStore;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** D1 飞行回放与统计的只读 HTTP 入口。 */
@RestController
public final class FlightQueryController {

    private final FlightQueryService queryService;
    private final OrderTaskMapper orders;
    private final TaskLogMapper logs;
    private final AlertMapper alerts;
    private final PlanTrackMapper plans;
    private final ShipPredictionReplayStore predictionReplay;
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    public FlightQueryController(FlightQueryService queryService) {
        this(queryService, null, null, null, null, null);
    }

    @Autowired
    public FlightQueryController(FlightQueryService queryService, OrderTaskMapper orders,
            TaskLogMapper logs, AlertMapper alerts, PlanTrackMapper plans,
            ShipPredictionReplayStore predictionReplay) {
        this.queryService = queryService;
        this.orders = orders;
        this.logs = logs;
        this.alerts = alerts;
        this.plans = plans;
        this.predictionReplay = predictionReplay;
    }

    /**
     * 返回前端实时处理器可直接重放的历史帧；记录顺序由仓内时间排序保证。
     * 未知任务使用明确的 404 语义，避免把“任务不存在”误显示成“任务没有轨迹”。
     */
    @GetMapping(ApiPath.REPLAY_TEMPLATE)
    public FlightReplayResponse replay(@PathVariable("taskId") String taskId) {
        OrderTaskEntity order = orders == null ? null : orders.selectById(taskId);
        String flightId = order == null ? taskId : order.getDroneId();
        if (order != null && (flightId == null || flightId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单尚未分配无人机：" + taskId);
        }
        List<TelemetryState> states = queryService.replay(flightId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到飞行任务：" + taskId);
        }
        long start = states.stream().mapToLong(TelemetryState::timestamp).min().orElseThrow();
        long end = states.stream().mapToLong(TelemetryState::timestamp).max().orElseThrow();
        if (order == null) {
            if (orders != null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到订单：" + taskId);
            }
            return new FlightReplayResponse(taskId, taskId, states.stream()
                    .map(FlightQueryController::toDroneStateFrame).toList());
        }
        String orderId = order.getOrderId();
        if (order.getCreateTime() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单缺少创建时刻，无法界定回放窗口");
        }
        start = Math.max(start, epoch(order.getCreateTime()));
        OrderTaskEntity nextOrder = orders.selectOne(new QueryWrapper<OrderTaskEntity>()
                .eq("drone_id", flightId).gt("create_time", order.getCreateTime())
                .orderByAsc("create_time").last("LIMIT 1"));
        if (nextOrder != null && nextOrder.getCreateTime() != null) {
            end = Math.min(end, epoch(nextOrder.getCreateTime()) - 1);
        }
        if (end < start) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该订单时间窗内没有飞行记录");
        }
        List<FlightReplayResponse.Frame> frames = new ArrayList<>();
        long windowStart = start;
        long windowEnd = end;
        states.stream().filter(state -> state.timestamp() >= windowStart && state.timestamp() <= windowEnd)
                .forEach(state -> frames.add(toDroneStateFrame(state)));
        addOrderFrames(frames, orderId, start, end);
        addAlarmFrames(frames, orderId, start, end);
        addPlanFrames(frames, orderId, start, end);
        if (predictionReplay != null && order.getShipMmsi() != null) {
            predictionReplay.range(order.getShipMmsi(), start, end).forEach(frame ->
                    frames.add(FlightReplayResponse.Frame.ship(frame, frame.t0())));
        }
        frames.sort(Comparator.comparingLong(FlightReplayResponse.Frame::eventTs));
        return new FlightReplayResponse(flightId, orderId, frames);
    }

    /** 飞行记录仓统计；不占用调度侧的 /api/monitor/statistics。 */
    @GetMapping(ApiPath.REPLAY_STATISTICS)
    public Map<String, Object> statistics() {
        return queryService.statistics();
    }

    private static FlightReplayResponse.Frame toDroneStateFrame(TelemetryState state) {
        var telemetry = state.telemetry();
        return FlightReplayResponse.Frame.drone(new DroneStateFrame(
                telemetry.uavId(), telemetry.lon(), telemetry.lat(), telemetry.battery(),
                null, telemetry.ts(), telemetry.heading(), null));
    }

    private void addOrderFrames(List<FlightReplayResponse.Frame> frames, String orderId, long start, long end) {
        if (logs == null) return;
        List<TaskLogEntity> rows = logs.selectList(new QueryWrapper<TaskLogEntity>()
                .eq("order_id", orderId).ge("ts", local(start)).le("ts", local(end))
                .orderByAsc("ts", "log_id"));
        if (rows == null) return;
        for (TaskLogEntity row : rows) {
            if (row.getToStatus() == null || row.getTs() == null || row.getLogId() == null
                    || !isOrderTransition(row)) continue;
            try {
                OrderStatus status = OrderStatus.fromValue(row.getToStatus());
                long ts = epoch(row.getTs());
                Map<String, Object> data = Map.of("orderId", orderId,
                        "orderStatus", status.getValue(), "eventTs", ts, "stateVersion", row.getLogId() + 1);
                frames.add(FlightReplayResponse.Frame.order(data, ts));
            } catch (IllegalArgumentException ignored) {
                // Task logs also contain goods and drone state changes.
            }
        }
    }

    private static boolean isOrderTransition(TaskLogEntity row) {
        if (row.getOpType() == null) return false;
        if ("订单接入".equals(row.getOpType())) return true;
        if (row.getFromStatus() == null) return false;
        try {
            OrderStatus.fromValue(row.getFromStatus());
            OrderStatus.fromValue(row.getToStatus());
            return !row.getFromStatus().equals(row.getToStatus());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void addAlarmFrames(List<FlightReplayResponse.Frame> frames, String orderId, long start, long end) {
        if (alerts == null) return;
        List<AlertEntity> rows = alerts.selectList(new QueryWrapper<AlertEntity>()
                .eq("order_id", orderId).ge("trigger_time", local(start)).le("trigger_time", local(end))
                .orderByAsc("trigger_time", "alert_id"));
        if (rows == null) return;
        for (AlertEntity row : rows) {
            if (row.getTriggerTime() == null) continue;
            try {
                AlarmFrame data = new AlarmFrame(AlertType.fromValue(row.getAlertType()),
                        AlertLevel.fromValue(row.getLevel()), row.getDroneId(),
                        row.getAlertType(), row.getLon(), row.getLat(), orderId, row.getAlertId());
                frames.add(FlightReplayResponse.Frame.alarm(data, epoch(row.getTriggerTime())));
            } catch (IllegalArgumentException ignored) {
                // Invalid historical enum values do not become fabricated alerts.
            }
        }
    }

    private void addPlanFrames(List<FlightReplayResponse.Frame> frames, String orderId, long start, long end) {
        if (plans == null) return;
        List<PlanTrackEntity> rows = plans.selectByOrderId(orderId);
        if (rows == null || rows.isEmpty()) return;
        Map<String, Map<String, List<PlanTrackFrame.Point>>> routes = new LinkedHashMap<>();
        Map<String, String> comboIds = new LinkedHashMap<>();
        Map<String, Long> created = new LinkedHashMap<>();
        for (PlanTrackEntity row : rows) {
            if (row.getRouteId() == null || row.getSegmentKind() == null || row.getLon() == null
                    || row.getLat() == null || row.getTMs() == null) continue;
            routes.computeIfAbsent(row.getRouteId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getSegmentKind(), ignored -> new ArrayList<>())
                    .add(new PlanTrackFrame.Point(row.getLon().doubleValue(), row.getLat().doubleValue(),
                            row.getAltM() == null ? 0 : row.getAltM().doubleValue(),
                            row.getSpeedMs() == null ? 0 : row.getSpeedMs().doubleValue(), row.getTMs()));
            comboIds.putIfAbsent(row.getRouteId(), row.getComboId());
            if (row.getCreatedAt() != null) created.merge(row.getRouteId(), epoch(row.getCreatedAt()), Math::min);
        }
        for (var route : routes.entrySet()) {
            List<PlanTrackFrame.Segment> segments = route.getValue().entrySet().stream()
                    .map(entry -> new PlanTrackFrame.Segment(entry.getKey(), entry.getValue())).toList();
            if (segments.isEmpty()) continue;
            Long ts = created.get(route.getKey());
            if (ts == null || ts < start || ts > end) continue;
            frames.add(FlightReplayResponse.Frame.plan(new PlanTrackFrame(orderId, route.getKey(),
                    comboIds.get(route.getKey()), PlanTrackFrame.OP_TYPE, segments), ts));
        }
    }

    private static LocalDateTime local(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(DB_ZONE).toLocalDateTime();
    }
    private static long epoch(LocalDateTime value) {
        return value.atZone(DB_ZONE).toInstant().toEpochMilli();
    }
}
