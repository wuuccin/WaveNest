package com.waterlogistics.monitor.track;

import com.waterlogistics.algo.measurement.TelemetryState;
import com.waterlogistics.storage.entity.FlightPointEntity;
import com.waterlogistics.storage.repository.FlightPointMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Writes each cleaned, geometry-usable actual-flight point to {@code flight_point}. */
@Component
public final class DatabaseFlightPointSink implements FlightPointSink {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFlightPointSink.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private final FlightPointMapper flightPointMapper;

    public DatabaseFlightPointSink(FlightPointMapper flightPointMapper) {
        this.flightPointMapper = flightPointMapper;
    }

    @Override
    public void persist(TelemetryState state, FlightContextProvider.FlightContext context) {
        if (state == null || !state.geometryUsable()) return;
        try {
            FlightPointEntity entity = new FlightPointEntity();
            entity.setPointId(UUID.randomUUID().toString());
            entity.setDroneId(state.flightId());
            entity.setOrderId(context == null ? null : context.orderId());
            entity.setTs(Instant.ofEpochMilli(state.timestamp()).atZone(DB_ZONE).toLocalDateTime());
            entity.setLon(decimal(state.longitude(), 7));
            entity.setLat(decimal(state.latitude(), 7));
            entity.setAlt(decimal(state.altitude(), 2));
            entity.setSpeed(decimal(state.groundSpeed(), 2));
            entity.setHeading(decimal(state.track(), 2));
            entity.setSegmentType(context == null ? null : context.segmentType());
            flightPointMapper.insert(entity);
        } catch (RuntimeException error) {
            log.warn("清洗后航迹点落库失败，不阻断实时检测：droneId={} ts={} err={}",
                    state.flightId(), state.timestamp(), error.getMessage());
        }
    }

    private static BigDecimal decimal(Double value, int scale) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
