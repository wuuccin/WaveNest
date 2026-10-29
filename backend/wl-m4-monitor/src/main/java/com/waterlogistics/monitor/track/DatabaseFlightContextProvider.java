package com.waterlogistics.monitor.track;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.measurement.Geo;
import com.waterlogistics.algo.measurement.RouteMeasurement;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Database-backed T2 straight-baseline flight context. */
@Component
public final class DatabaseFlightContextProvider implements FlightContextProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFlightContextProvider.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> ACTIVE_ORDER_STATUSES = List.of(
            "已派发", "去程中", "末段交接中", "返航中");

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DroneMapper droneMapper;
    private final NestMapper nestMapper;

    public DatabaseFlightContextProvider(OrderTaskMapper orderTaskMapper,
                                         TaskLogMapper taskLogMapper,
                                         DroneMapper droneMapper,
                                         NestMapper nestMapper) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
    }

    @Override
    public FlightContext resolve(String droneId) {
        if (droneId == null || droneId.isBlank()) return null;
        try {
            List<OrderTaskEntity> candidates = orderTaskMapper.selectList(
                    new LambdaQueryWrapper<OrderTaskEntity>()
                            .eq(OrderTaskEntity::getDroneId, droneId)
                            .in(OrderTaskEntity::getStatus, ACTIVE_ORDER_STATUSES)
                            .isNotNull(OrderTaskEntity::getMeetLon)
                            .isNotNull(OrderTaskEntity::getMeetLat)
                            .orderByDesc(OrderTaskEntity::getMeetTime)
                            .orderByDesc(OrderTaskEntity::getCreateTime));
            if (candidates == null) return null;
            for (OrderTaskEntity order : candidates) {
                FlightCycle cycle = activeCycle(order);
                if (cycle == null) continue;
                DroneEntity drone = droneMapper.selectById(droneId);
                if (drone == null || drone.getHomeNestId() == null) continue;
                NestEntity nest = nestMapper.selectById(drone.getHomeNestId());
                if (nest == null || nest.getLon() == null || nest.getLat() == null) continue;

                Geo.Point start = new Geo.Point(nest.getLon().doubleValue(), nest.getLat().doubleValue());
                Geo.Point meeting = new Geo.Point(
                        order.getMeetLon().doubleValue(), order.getMeetLat().doubleValue());
                long version = order.getMeetTime() == null
                        ? Math.max(0L, cycle.dispatchLogId())
                        : order.getMeetTime().atZone(DB_ZONE).toInstant().toEpochMilli();
                RouteMeasurement.Route route = new RouteMeasurement.Route(
                        "straight:" + order.getOrderId(), version, List.of(start, meeting));
                RouteMeasurement.Landing landing = new RouteMeasurement.Landing(
                        "会面点", order.getOrderId(), meeting, version, 1);
                return new FlightContext(order.getOrderId(), cycle.segmentType(), route, landing);
            }
        } catch (RuntimeException error) {
            log.warn("飞行上下文查询失败，当前帧降级为无偏航投影：droneId={} err={}",
                    droneId, error.getMessage());
        }
        return null;
    }

    private FlightCycle activeCycle(OrderTaskEntity order) {
        List<TaskLogEntity> logs = taskLogMapper.selectList(
                new LambdaQueryWrapper<TaskLogEntity>()
                        .eq(TaskLogEntity::getOrderId, order.getOrderId())
                        .orderByDesc(TaskLogEntity::getLogId));
        if (logs == null) return null;
        for (TaskLogEntity logEntry : logs) {
            if ("派发".equals(logEntry.getOpType())) {
                return new FlightCycle(logEntry.getLogId() == null ? 0L : logEntry.getLogId(), "去程");
            }
            if ("已完成".equals(logEntry.getToStatus())) return null;
            if ("返航中".equals(logEntry.getToStatus())) {
                return activeAfterLastDispatch(logs, "返程");
            }
        }
        return null;
    }

    private FlightCycle activeAfterLastDispatch(List<TaskLogEntity> logs, String segmentType) {
        for (TaskLogEntity entry : logs) {
            if ("派发".equals(entry.getOpType())) {
                return new FlightCycle(entry.getLogId() == null ? 0L : entry.getLogId(), segmentType);
            }
        }
        return null;
    }

    private record FlightCycle(long dispatchLogId, String segmentType) {
    }
}
