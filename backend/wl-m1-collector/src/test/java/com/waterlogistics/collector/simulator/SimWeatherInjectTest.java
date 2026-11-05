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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 气象注入：把「指定的风」按 U-05 甲案发出去。
 *
 * <p>本类守两条：
 * <ol>
 *   <li><b>粒度</b>：注入一片风＝每个网格点各一条（多位置），不是一条全局代表值；</li>
 *   <li><b>持续</b>：注入后自产风保持这片海况，不在一秒后被打回基准值
 *       （否则演示时"风来了又把机型刷掉了"看不见）。</li>
 * </ol>
 */
class SimWeatherInjectTest {

    private RecordingPublisher publisher;
    private SimWeatherConnector sim;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimWeatherConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        publisher.clear();
    }

    @Test
    @DisplayName("注入一片风：每个网格点各发一条，位置互不相同（不是一条全局值）")
    void injectPublishesOnePerGridPoint() {
        int sent = sim.inject(11.0, 135.0, Double.NaN, true);

        assertEquals(4, sent, "2×2 网格应发 4 条");
        List<WeatherData> out = publisher.weather();
        assertEquals(4, out.size());

        Set<String> positions = new HashSet<>();
        for (WeatherData w : out) {
            positions.add(w.lon() + "," + w.lat());
            assertEquals(11.0, w.windSpeedMs(), 1e-9, "注入值应逐点一致");
            assertEquals(135.0, w.windDirDeg(), 1e-9);
        }
        assertEquals(4, positions.size(), "四个网格点坐标必须互不相同——这就是'按位置多条'");
    }

    @Test
    @DisplayName("注入后自产风保持注入海况（关键：否则注入一秒后被冲掉，演示看不见）")
    void biasKeepsSubsequentPollsAtInjectedSeaState() {
        sim.inject(11.0, 135.0, Double.NaN, true);
        publisher.clear();

        sim.pollOnce(1_000L);
        for (WeatherData w : publisher.weather()) {
            // 偏置 11-5=6，叠加波动 ±1.5 与抖动 ±0.3 → 应落在 11±1.8 附近
            assertTrue(w.windSpeedMs() >= 11.0 - 1.8 - 1e-9 && w.windSpeedMs() <= 11.0 + 1.8 + 1e-9,
                    "注入后自产风应保持在注入海况附近，实际 " + w.windSpeedMs());
        }
    }

    @Test
    @DisplayName("persist=false：只发这一轮，不改后续自产海况")
    void transientInjectionDoesNotBias() {
        sim.inject(11.0, 135.0, Double.NaN, false);
        assertEquals(4, publisher.weather().size(), "这一轮照发 4 条");
        assertNull(sim.currentBias(), "不应留下偏置");

        publisher.clear();
        sim.pollOnce(1_000L);
        for (WeatherData w : publisher.weather()) {
            assertTrue(w.windSpeedMs() < 8.0, "后续自产风应回到基准带，实际 " + w.windSpeedMs());
        }
    }

    @Test
    @DisplayName("清除偏置：自产风回到基准海况")
    void clearBiasRestoresBaseline() {
        sim.inject(11.0, 135.0, Double.NaN, true);
        assertNotNull(sim.currentBias());

        sim.clearBias();
        assertNull(sim.currentBias());

        publisher.clear();
        sim.pollOnce(1_000L);
        for (WeatherData w : publisher.weather()) {
            assertTrue(w.windSpeedMs() >= 5.0 - 1.8 - 1e-9 && w.windSpeedMs() <= 5.0 + 1.8 + 1e-9,
                    "清偏置后应回基准 5.0 附近，实际 " + w.windSpeedMs());
        }
    }

    @Test
    @DisplayName("阵风缺省取风速×1.2；显式给出时不得低于风速")
    void gustDefaultsAndValidates() {
        sim.inject(10.0, 90.0, Double.NaN, false);
        for (WeatherData w : publisher.weather()) {
            assertEquals(12.0, w.gustMs(), 1e-9, "缺省阵风应为 wind×1.2");
        }

        publisher.clear();
        sim.inject(10.0, 90.0, 13.5, false);
        assertEquals(13.5, publisher.weather().get(0).gustMs(), 1e-9);

        assertThrows(IllegalArgumentException.class,
                () -> sim.inject(10.0, 90.0, 8.0, false),
                "阵风低于风速应拒绝（物理不成立）");
    }

    @Test
    @DisplayName("非法入参被拒绝：负风速 / NaN 风向")
    void rejectsIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> sim.inject(-1.0, 90.0, Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> sim.inject(Double.NaN, 90.0, Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> sim.inject(5.0, Double.NaN, Double.NaN, false));
        assertEquals(0, publisher.weather().size(), "非法入参不应发出任何消息");
    }

    @Test
    @DisplayName("风向自动归一到 [0,360)")
    void directionIsNormalized() {
        sim.inject(5.0, 370.0, Double.NaN, false);
        assertEquals(10.0, publisher.weather().get(0).windDirDeg(), 1e-9);

        publisher.clear();
        sim.inject(5.0, -30.0, Double.NaN, false);
        assertEquals(330.0, publisher.weather().get(0).windDirDeg(), 1e-9);
    }

    @Test
    @DisplayName("注入计数累加（验收锚点）")
    void countsInjections() {
        assertEquals(0, sim.injectedCount());
        sim.inject(11.0, 135.0, Double.NaN, true);
        sim.inject(11.0, 135.0, Double.NaN, true);
        assertEquals(2, sim.injectedCount());
    }

    @Test
    @DisplayName("注入的时间语义与自产一致：obsTs ＝ targetTs ＝ ts")
    void injectedTimestampsFollowSameRule() {
        sim.inject(11.0, 135.0, Double.NaN, false);
        for (WeatherData w : publisher.weather()) {
            assertEquals(w.obsTs(), w.targetTs(), "首版 targetTs 必须等于 obsTs");
            assertEquals(w.ts(), w.obsTs());
        }
    }
}
