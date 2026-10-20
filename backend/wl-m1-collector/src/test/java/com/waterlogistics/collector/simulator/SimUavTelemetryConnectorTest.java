package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.UavTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无人机遥测仿真连接器单测：机数、电量递减与下限、圆周巡航几何。
 */
class SimUavTelemetryConnectorTest {

    private RecordingPublisher publisher;
    private SimUavTelemetryConnector sim;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimUavTelemetryConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
    }

    @Test
    @DisplayName("每节拍按配置机数发遥测，编号从 UAV-01 起")
    void publishesConfiguredNumberOfDrones() {
        sim.pollOnce(1_000L);

        List<UavTelemetry> sent = publisher.telemetry();
        assertEquals(2, sent.size());
        assertEquals("UAV-01", sent.get(0).uavId());
        assertEquals("UAV-02", sent.get(1).uavId());
    }

    @Test
    @DisplayName("电量逐节拍递减，且永不低于 5% 下限")
    void batteryDrainsWithFloor() {
        for (int i = 0; i < 100; i++) {
            sim.pollOnce(i * 1_000L);
        }
        List<UavTelemetry> sent = publisher.telemetry();
        UavTelemetry first = sent.get(0);
        UavTelemetry last = sent.get(sent.size() - 2);   // 倒数第二条也是 UAV-01

        assertEquals(100.0, first.battery(), 1e-9, "首帧满电");
        assertTrue(last.battery() < first.battery(), "电量应随时间消耗");
        assertTrue(last.battery() >= 5.0, "电量不得跌破 5% 下限");
    }

    @Test
    @DisplayName("圆周巡航：到巢心距离 ≈ 配置半径")
    void orbitsAtConfiguredRadius() {
        sim.pollOnce(1_000L);
        UavTelemetry m = publisher.telemetry().get(0);

        double dLon = m.lon() - 114.305000;
        double dLat = m.lat() - 30.605000;
        double distDeg = Math.hypot(dLon, dLat);
        assertEquals(0.0030, distDeg, 1e-6, "应落在半径 0.003° 的圆上");
    }

    @Test
    @DisplayName("契约字段：高度分层、速度与航向在合理区间")
    void carriesSaneContractFields() {
        sim.pollOnce(1_000L);
        List<UavTelemetry> sent = publisher.telemetry();

        assertEquals(80.0, sent.get(0).altM(), 1e-9, "第 1 架 80m");
        assertEquals(90.0, sent.get(1).altM(), 1e-9, "第 2 架 90m（分层防混）");
        for (UavTelemetry m : sent) {
            assertEquals(12.0, m.speedMs(), 1e-9);
            assertTrue(m.heading() >= 0.0 && m.heading() < 360.0, "航向应在 [0,360)");
        }
    }

    @Test
    @DisplayName("未显式 start 时首次 pollOnce 自动初始化（容错）")
    void lazyInitialisesOnFirstPoll() {
        RecordingPublisher fresh = new RecordingPublisher();
        SimUavTelemetryConnector connector = new SimUavTelemetryConnector(fresh);
        FieldInjector.applySimDefaults(connector);

        connector.pollOnce(1_000L);
        assertEquals(2, fresh.telemetry().size(), "懒初始化后应正常产出");
    }
}
