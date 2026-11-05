package com.waterlogistics.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.storage.entity.FlightPointEntity;
import com.waterlogistics.storage.repository.FlightPointMapper;
import com.waterlogistics.monitor.ingest.UavTelemetryListener;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Opt-in database regression: set WAVENEST_T29_DB_TEST=true with a reachable local MySQL. */
@EnabledIfEnvironmentVariable(named = "WAVENEST_T29_DB_TEST", matches = "true")
@SpringBootTest(classes = WaveNestApplication.class, properties = {
        "server.port=0",
        "wavenest.sim.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class T29FlightPointIntegrationTest {

    @Autowired
    private UavTelemetryListener listener;

    @Autowired
    private FlightPointMapper flightPointMapper;

    @Test
    void cleanedTelemetryConsumedByM4_isWrittenToFlightPointTable() {
        String testDroneId = "T29-" + UUID.randomUUID();
        long firstTs = System.currentTimeMillis();
        var query = new LambdaQueryWrapper<FlightPointEntity>()
                .eq(FlightPointEntity::getDroneId, testDroneId);
        try {
            for (int index = 0; index < 3; index++) {
                listener.onTelemetryMessage(new UavTelemetry(
                        testDroneId,
                        115.860000 + index * 0.000120,
                        28.660000,
                        80.0,
                        12.0,
                        90.0 - index * 0.1,
                        90.0,
                        firstTs + index * 1_000L));
            }

            assertEquals(3L, flightPointMapper.selectCount(query),
                    "M4 listener should persist each cleaned actual-flight sample");
        } finally {
            flightPointMapper.delete(query);
        }
    }
}
