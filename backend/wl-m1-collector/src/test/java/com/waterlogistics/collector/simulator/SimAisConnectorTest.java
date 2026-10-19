package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.AisMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AIS 仿真连接器单测：航迹推进方向、变向持续性、重置可复现。
 *
 * <p>验收锚点（《编码分工表》①）：点「变向」后航向角改变并<b>持续</b>发新航迹。
 */
class SimAisConnectorTest {

    private RecordingPublisher publisher;
    private SimAisConnector sim;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimAisConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
    }

    @Test
    @DisplayName("节拍推进：每 tick 发一条，初始航向 90° 时经度增大、纬度基本不变")
    void advancesAlongHeading() {
        sim.pollOnce(1_000L);
        sim.pollOnce(2_000L);

        List<AisMessage> sent = publisher.ais();
        assertEquals(2, sent.size());
        AisMessage first = sent.get(0);
        AisMessage second = sent.get(1);

        assertEquals(90.0, first.cog(), 1e-9);
        assertTrue(first.lon() > 114.300000, "正东航向经度应增大");
        assertEquals(30.600000, first.lat(), 1e-6, "纬度不应漂移");
        assertTrue(second.lon() > first.lon(), "第二个节拍应继续前进");
    }

    @Test
    @DisplayName("变向 60° 后：新航向生效，且之后每个节拍都持续带新航向")
    void turnPersistsAcrossTicks() {
        sim.pollOnce(1_000L);
        double newCog = sim.injectTurn(60.0);
        assertEquals(150.0, newCog, 1e-9);

        publisher.clear();
        sim.pollOnce(2_000L);
        sim.pollOnce(3_000L);
        sim.pollOnce(4_000L);

        List<AisMessage> after = publisher.ais();
        assertEquals(3, after.size(), "变向后每个节拍都应继续发数据");
        for (AisMessage m : after) {
            assertEquals(150.0, m.cog(), 1e-9, "变向必须持续，不能只改一帧");
        }
        assertTrue(after.get(2).lat() < after.get(0).lat(), "150° 航向纬度应递减");
    }

    @Test
    @DisplayName("连点 10 次每次都改变航向（每次 +10°，累计 +100°）")
    void tenConsecutiveTurnsEachTakeEffect() {
        double cog = sim.currentCog();
        for (int i = 0; i < 10; i++) {
            double next = sim.injectTurn(10.0);
            assertTrue(Math.abs(normalize(next - cog) - 10.0) < 1e-9,
                    "第 " + (i + 1) + " 次注入未生效");
            cog = next;
        }
        assertEquals(190.0, sim.currentCog(), 1e-9);
    }

    @Test
    @DisplayName("航向归一化：-90° → 270°，450° → 90°")
    void normalizesHeading() {
        assertEquals(270.0, sim.setCog(-90.0), 1e-9);
        assertEquals(90.0, sim.setCog(450.0), 1e-9);
    }

    @Test
    @DisplayName("reset 后回到起点与初始航向（演示可反复重演）")
    void resetRestoresInitialState() {
        sim.injectTurn(120.0);
        sim.pollOnce(1_000L);
        sim.pollOnce(2_000L);

        sim.reset();
        assertEquals(90.0, sim.currentCog(), 1e-9);

        publisher.clear();
        sim.pollOnce(3_000L);
        AisMessage m = publisher.ais().get(0);
        assertTrue(Math.abs(m.lon() - 114.300000) < 0.001, "reset 后应从起点附近重新出发");
        assertEquals(30.600000, m.lat(), 1e-6);
    }

    @Test
    @DisplayName("契约字段：mmsi / 船名 / 时间戳原样透传")
    void carriesContractFields() {
        sim.pollOnce(1_234L);
        AisMessage m = publisher.ais().get(0);
        assertEquals("123456789", m.mmsi());
        assertEquals("SIM-SHIP-01", m.shipName());
        assertEquals(1_234L, m.ts());
    }

    private static double normalize(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }
}
