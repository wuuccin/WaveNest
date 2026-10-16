package com.waterlogistics.app.push;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.contract.mq.TaskPlanMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.contract.ws.WsFrameType;
import com.waterlogistics.monitor.ingest.TelemetryService;
import com.waterlogistics.monitor.store.FlightRecordStore;
import com.waterlogistics.monitor.track.TaskMeasurementProvider;
import com.waterlogistics.storage.entity.FlightPointEntity;
import com.waterlogistics.storage.repository.FlightPointMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
class TelemetryWiring {
    @Bean TaskMeasurementProvider taskMeasurementProvider(FlightRecordStore records) {
        return new TaskMeasurementProvider(records);
    }
    @Bean
    @ConditionalOnProperty(prefix = "wavenest.telemetry", name = "enabled", havingValue = "true")
    TelemetryService telemetryService(
            @Value("${wavenest.telemetry.max-speed-ms}") double maxSpeed,
            @Value("${wavenest.telemetry.max-altitude-m}") double maxAltitude,
            @Value("${wavenest.telemetry.max-vertical-rate-ms}") double maxVerticalRate,
            @Value("${wavenest.telemetry.max-acceleration-ms2}") double maxAcceleration,
            @Value("${wavenest.telemetry.max-turn-rate-deg-sec}") double maxTurnRate,
            @Value("${wavenest.telemetry.sampling-ms}") long samplingMs,
            @Value("${wavenest.telemetry.speed-mad-threshold}") double madThreshold) {
        return new TelemetryService(new TelemetryCleaner.Limits(
                maxSpeed, maxAltitude, maxVerticalRate, maxAcceleration, maxTurnRate, samplingMs),
                madThreshold);
    }
}

/** One consumer for the established M1 data.uav.telemetry feed. */
@Component
@ConditionalOnProperty(prefix = "wavenest.telemetry", name = "enabled", havingValue = "true")
public final class TelemetryIngress {
    private static final Logger log = LoggerFactory.getLogger(TelemetryIngress.class);
    private final TelemetryService telemetry;
    private final TaskMeasurementProvider measurements;
    private final FlightRecordStore records;
    private final WsPushEndpoint push;
    private final FlightPointMapper flightPoints;
    public TelemetryIngress(TelemetryService telemetry, TaskMeasurementProvider measurements,
                           FlightRecordStore records, WsPushEndpoint push, FlightPointMapper flightPoints) {
        this.telemetry = telemetry; this.measurements = measurements; this.records = records; this.push = push;
        this.flightPoints = flightPoints;
    }
    @RabbitListener(queues = "${wavenest.mq.data-uav-queue}")
    public void onTelemetry(UavTelemetry raw) {
        try {
            var state = telemetry.acceptState(raw);
            var plan = measurements.planFor(raw.uavId());
            if (plan == null) return;
            records.append(plan.taskId(), state);
            measurements.measure(state);
            if (state.geometryUsable() && !state.anomalyFlags().contains("POSITION_IMPUTED")) {
                var point = new FlightPointEntity();
                point.setPointId(UUID.randomUUID().toString());
                point.setDroneId(raw.uavId());
                point.setOrderId(plan.orderId());
                point.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(raw.ts()), ZoneId.of("Asia/Shanghai")));
                point.setLon(BigDecimal.valueOf(state.longitude()));
                point.setLat(BigDecimal.valueOf(state.latitude()));
                if (state.altitude() != null) point.setAlt(BigDecimal.valueOf(state.altitude()));
                if (state.groundSpeed() != null) point.setSpeed(BigDecimal.valueOf(state.groundSpeed()));
                if (state.track() != null) point.setHeading(BigDecimal.valueOf(state.track()));
                flightPoints.insert(point);
            }
            if (state.geometryUsable() && state.battery() != null) push.publishForTask(plan.taskId(), WsFrameType.DRONE_STATE.getType(),
                    new DroneStateFrame(raw.uavId(), state.longitude(), state.latitude(),
                            state.battery(), "状态待确认", raw.ts()));
        } catch (IllegalArgumentException e) { log.warn("Dropped invalid UAV telemetry: {}", e.getMessage()); }
    }
    @RabbitListener(queues = "${wavenest.mq.task-plan-queue}")
    public void onTaskPlan(TaskPlanMessage plan) {
        measurements.acceptPlan(plan);
        push.publishForTask(plan.taskId(), WsFrameType.RENDEZVOUS.getType(),
                new RendezvousFrame(plan.orderId(), plan.meetLon(), plan.meetLat(), true));
        if (plan.shipTrack() != null) push.publishForTask(plan.taskId(), WsFrameType.SHIP_TRAJ.getType(), plan.shipTrack());
    }
}
