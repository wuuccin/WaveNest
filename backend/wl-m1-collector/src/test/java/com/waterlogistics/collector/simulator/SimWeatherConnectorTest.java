package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.WeatherData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 气象仿真连接器单测：U-05 两条硬口径。
 *
 * <ol>
 *   <li>发布粒度＝按位置多条（2×2 网格 → 每节拍 4 条），不得只发一条全局代表值；</li>
 *   <li>时间语义＝同时带 obsTs 与 targetTs，首版 targetTs ＝ obsTs（实测代偿）。</li>
 * </ol>
 */
class SimWeatherConnectorTest {

    private RecordingPublisher publisher;
    private SimWeatherConnector sim;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimWeatherConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
    }

    @Test
    @DisplayName("启动即发初值帧：4 个网格点各一条，值为配置基准（10-16 气象初值要求）")
    void startPublishesInitialSnapshot() {
        // 就绪事件前 start() 不发初值：此时 MQ 交换机未声明，发了必丢（10-20 核对实录）
        publisher.clear();
        sim.start();
        assertEquals(0, publisher.weather().size(), "就绪前不应发初值");

        sim.onApplicationReady();
        List<WeatherData> initial = publisher.weather();
        assertEquals(4, initial.size(), "2×2 网格应发 4 条，而非 1 条全局值");
        for (WeatherData w : initial) {
            assertEquals(5.0, w.windSpeedMs(), 1e-9, "初值风速应为基准 5.0");
            assertEquals(135.0, w.windDirDeg(), 1e-9, "初值风向应为基准 135°");
            assertEquals(8.0, w.gustMs(), 1e-9, "初值阵风应为 base+gustAdd");
        }
    }

    @Test
    @DisplayName("就绪事件幂等：只发一轮初值，不重发")
    void readyEventIsIdempotent() {
        sim.onApplicationReady();
        sim.onApplicationReady();
        sim.onApplicationReady();
        assertEquals(4, publisher.weather().size(), "多次就绪事件只发一轮");
    }

    @Test
    @DisplayName("按位置多条：每节拍 4 条且四个坐标互不相同")
    void publishesOneMessagePerGridPoint() {
        publisher.clear();
        sim.pollOnce(1_000L);

        List<WeatherData> sent = publisher.weather();
        assertEquals(4, sent.size());

        Set<String> positions = new HashSet<>();
        for (WeatherData w : sent) {
            positions.add(w.lon() + "," + w.lat());
        }
        assertEquals(4, positions.size(), "四个网格点坐标必须互不相同");
        assertTrue(positions.contains("114.29,30.59"), "应包含网格左下角");
        assertTrue(positions.contains("114.3,30.6"), "应包含网格右上角");
    }

    @Test
    @DisplayName("时间语义：obsTs ＝ targetTs ＝ tickTs（首版实测代偿）")
    void carriesObsAndTargetTimestamps() {
        publisher.clear();
        sim.pollOnce(12_345L);

        for (WeatherData w : publisher.weather()) {
            assertEquals(12_345L, w.ts());
            assertEquals(w.obsTs(), w.targetTs(), "首版 targetTs 必须等于 obsTs");
            assertEquals(12_345L, w.obsTs());
        }
    }

    @Test
    @DisplayName("物理合理性：阵风 ≥ 风速，风向落在 [0,360)")
    void producesPhysicallySaneValues() {
        for (int i = 0; i < 20; i++) {
            sim.pollOnce(1_000L + i * 1_000L);
        }
        for (WeatherData w : publisher.weather()) {
            assertTrue(w.gustMs() >= w.windSpeedMs(), "阵风不应小于平均风速");
            assertTrue(w.windDirDeg() >= 0.0 && w.windDirDeg() < 360.0, "风向应在 [0,360)");
            assertTrue(w.windSpeedMs() > 0.0, "仿真风速应恒正（不模拟静风）");
        }
    }

    @Test
    @DisplayName("风速随节拍波动（不是死值），且幅度不超过基准 ± 抖动")
    void windVariesButStaysInBand() {
        publisher.clear();
        for (int i = 0; i < 30; i++) {
            sim.pollOnce(i * 1_000L);
        }
        Set<Double> distinct = new HashSet<>();
        for (WeatherData w : publisher.weather()) {
            distinct.add(w.windSpeedMs());
            // 波动幅度：sin 项 ±1.5 + 随机 ±0.3
            assertTrue(w.windSpeedMs() >= 5.0 - 1.5 - 0.3 - 1e-9
                            && w.windSpeedMs() <= 5.0 + 1.5 + 0.3 + 1e-9,
                    "风速越出仿真带：" + w.windSpeedMs());
        }
        assertFalse(distinct.size() <= 1, "风速应随时间变化，不能是常量");
    }
}
