package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.WeatherData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 气象仿真连接器单测：U-05 两条硬口径 + 11-05 硬判据 #6。
 *
 * <ol>
 *   <li>发布粒度＝按位置多条（3×3 网格 → 每节拍 9 条），不得只发一条全局代表值；</li>
 *   <li>时间语义＝同时带 obsTs 与 targetTs，11-05 起 targetTs ＝ obsTs + 预报时距（300 s）；</li>
 *   <li>空间相关＝全网格共享相位，格间差只由 (ix+iy) 梯度与 ≤0.2 m/s 噪声决定，
 *       相邻格点风速差随格距单调递增。</li>
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
    @DisplayName("启动即发初值帧：9 个网格点各一条，值为配置基准 + 空间梯度（10-16 气象初值要求）")
    void startPublishesInitialSnapshot() {
        // 就绪事件前 start() 不发初值：此时 MQ 交换机未声明，发了必丢（10-20 核对实录）
        publisher.clear();
        sim.start();
        assertEquals(0, publisher.weather().size(), "就绪前不应发初值");

        sim.onApplicationReady();
        List<WeatherData> initial = publisher.weather();
        assertEquals(9, initial.size(), "3×3 网格应发 9 条，而非 1 条全局值");

        // 初值帧噪声为 0，故每个点的值可用 windAt(0, ix, iy, 0) 精确算出：
        // 风速 = 5.0 + (ix+iy)*0.5，风向 = 135.0 + (ix+iy)*1.5
        for (WeatherData w : initial) {
            int ix = (int) Math.round((w.lon() - 115.840) / 0.030);
            int iy = (int) Math.round((w.lat() - 28.640) / 0.030);
            assertTrue(ix >= 0 && ix < 3 && iy >= 0 && iy < 3, "格点号应落在 0..2：" + w);
            assertEquals(5.0 + (ix + iy) * 0.5, w.windSpeedMs(), 1e-9,
                    "初值风速应为基准 + 空间梯度（格 " + ix + "," + iy + "）");
            assertEquals(135.0 + (ix + iy) * 1.5, w.windDirDeg(), 1e-9,
                    "初值风向应为基准 + 空间梯度（格 " + ix + "," + iy + "）");
            assertEquals(w.windSpeedMs() + 3.0, w.gustMs(), 1e-9, "初值阵风应为风速 + gustAdd");
        }
    }

    @Test
    @DisplayName("就绪事件幂等：只发一轮初值，不重发")
    void readyEventIsIdempotent() {
        sim.onApplicationReady();
        sim.onApplicationReady();
        sim.onApplicationReady();
        assertEquals(9, publisher.weather().size(), "多次就绪事件只发一轮");
    }

    @Test
    @DisplayName("按位置多条：每节拍 9 条且九个坐标互不相同，含中心线两端与两巢")
    void publishesOneMessagePerGridPoint() {
        publisher.clear();
        sim.pollOnce(1_000L);

        List<WeatherData> sent = publisher.weather();
        assertEquals(9, sent.size());

        Set<String> positions = new HashSet<>();
        for (WeatherData w : sent) {
            positions.add(w.lon() + "," + w.lat());
        }
        assertEquals(9, positions.size(), "九个网格点坐标必须互不相同");

        // 3×3、步长 0.030°→ 经度 115.840–115.900，纬度 28.640–28.700（含端点）
        assertTrue(positions.contains("115.84,28.64"), "应包含网格左下角（南巢西南余量）");
        assertTrue(positions.contains("115.9,28.7"), "应包含网格右上角（罩住北巢 28.698）");
        assertTrue(positions.contains("115.84,28.7") && positions.contains("115.9,28.64"),
                "应包含另外两个角点");
        assertTrue(positions.contains("115.87,28.67"), "应包含网心（船起点最近的格点）");
    }

    @Test
    @DisplayName("覆盖航段：网格东南—西北两端都罩住两巢（北巢 28.698 / 南巢 28.642 在网内）")
    void gridCoversBothNests() {
        SimWeatherConnector.GridSpec spec = sim.gridSpec();
        assertEquals(3, spec.grid());
        assertEquals(0.030, spec.stepDeg(), 1e-12);
        assertEquals(115.900, spec.lonMax(), 1e-9, "东界应到 115.900（≥ 北巢 115.867）");
        assertEquals(28.700, spec.latMax(), 1e-9, "北界应到 28.700（≥ 北巢 28.698）");
        assertTrue(spec.lon0() <= 115.848, "西界应覆盖南巢经度 115.848");
        assertTrue(spec.lat0() <= 28.642, "南界应覆盖南巢纬度 28.642");
        assertEquals(300_000L, spec.forecastHorizonMs(), "预报时距缺省 300 s");
    }

    @Test
    @DisplayName("时间语义：obsTs ＝ tickTs，targetTs ＝ obsTs + 300 s（硬判据 #6：两者必须不等）")
    void carriesObsAndTargetTimestamps() {
        publisher.clear();
        sim.pollOnce(12_345L);

        for (WeatherData w : publisher.weather()) {
            assertEquals(12_345L, w.ts());
            assertEquals(12_345L, w.obsTs());
            assertNotEquals(w.obsTs(), w.targetTs(), "targetTs 不得再等于 obsTs");
            assertEquals(300_000L, w.targetTs() - w.obsTs(), "targetTs 应前推预报时距 300 s");
        }
    }

    @Test
    @DisplayName("预报时距可配：改 forecastHorizonMs 后 targetTs 跟着变")
    void forecastHorizonIsConfigurable() {
        FieldInjector.set(sim, "forecastHorizonMs", 600_000L);
        publisher.clear();
        sim.pollOnce(1_000L);
        for (WeatherData w : publisher.weather()) {
            assertEquals(600_000L, w.targetTs() - w.obsTs(), "配置生效后应前推 600 s");
        }
    }

    @Test
    @DisplayName("物理合理性：阵风 ≥ 风速，风向落在 [0,360)，风速恒正")
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
    @DisplayName("风速随节拍波动（不是死值），且幅度不超过基准 + 梯度 + 抖动 + 噪声")
    void windVariesButStaysInBand() {
        publisher.clear();
        for (int i = 0; i < 30; i++) {
            sim.pollOnce(i * 1_000L);
        }
        Set<Double> distinct = new HashSet<>();
        for (WeatherData w : publisher.weather()) {
            distinct.add(w.windSpeedMs());
            // 波动幅度：sin 项 ±1.5 + 空间梯度 0..2.0 + 随机 ±0.2
            assertTrue(w.windSpeedMs() >= 5.0 - 1.5 - 0.2 - 1e-9
                            && w.windSpeedMs() <= 5.0 + 2.0 + 1.5 + 0.2 + 1e-9,
                    "风速越出仿真带：" + w.windSpeedMs());
        }
        assertFalse(distinct.size() <= 1, "风速应随时间变化，不能是常量");
    }

    // ───────── 11-05 硬判据 #6：空间相关 ─────────

    /**
     * 取一帧的九点，按 (ix+iy) 分组求均值。
     *
     * <p>同一帧内 (ix+iy) 相同的点相位相同、梯度相同，理论上只差噪声；
     * 按组均值比逐点比更稳（噪声 ±0.2，组均值把三点平均后只剩 ±0.115）。
     */
    private static double meanWindByRing(List<WeatherData> frame, int ring) {
        double sum = 0.0;
        int n = 0;
        for (WeatherData w : frame) {
            int ix = (int) Math.round((w.lon() - 115.840) / 0.030);
            int iy = (int) Math.round((w.lat() - 28.640) / 0.030);
            if (ix + iy == ring) {
                sum += w.windSpeedMs();
                n++;
            }
        }
        assertTrue(n > 0, "第 " + ring + " 圈应至少有一个格点");
        return sum / n;
    }

    @Test
    @DisplayName("共享相位：同一帧内相邻格点风速差随格距单调递增（硬判据 #6）")
    void adjacentWindDifferenceGrowsWithDistance() {
        for (int round = 0; round < 20; round++) {
            publisher.clear();
            sim.pollOnce(round * 1_000L);
            List<WeatherData> frame = new ArrayList<>(publisher.weather());
            assertEquals(9, frame.size());

            // (ix+iy) 即该点到左下角的曼哈顿格距：0,1,1,2,2,2,3,3,4
            // 线性梯度下，圈均值的差应≈0.5/圈；噪声对组均值的影响远小于此
            double ring0 = meanWindByRing(frame, 0);
            double ring1 = meanWindByRing(frame, 1);
            double ring2 = meanWindByRing(frame, 2);
            double ring3 = meanWindByRing(frame, 3);
            double ring4 = meanWindByRing(frame, 4);

            assertTrue(ring1 > ring0, "第 1 圈应高于第 0 圈：+" + (ring1 - ring0));
            assertTrue(ring2 > ring1, "第 2 圈应高于第 1 圈：+" + (ring2 - ring1));
            assertTrue(ring3 > ring2, "第 3 圈应高于第 2 圈：+" + (ring3 - ring2));
            assertTrue(ring4 > ring3, "第 4 圈应高于第 3 圈：+" + (ring4 - ring3));

            // 跨圈差随格距单调：1 格差 < 2 格差 < 3 格差 < 4 格差
            double d1 = ring1 - ring0;
            double d2 = ring2 - ring0;
            double d3 = ring3 - ring0;
            double d4 = ring4 - ring0;
            assertTrue(d1 < d2 && d2 < d3 && d3 < d4,
                    "跨圈差应随格距单调递增，实际 d1=" + d1 + " d2=" + d2 + " d3=" + d3 + " d4=" + d4);
        }
    }

    @Test
    @DisplayName("共享相位：同一帧内九点相位一致——时间波动项不因格点而异")
    void temporalPhaseIsSharedAcrossGrid() {
        publisher.clear();
        sim.pollOnce(1_000L);
        List<WeatherData> frame = new ArrayList<>(publisher.weather());

        // 同一圈内各点同相位同梯度，差值只应来自噪声（≤2×0.2 = 0.4，取 0.45 留舍入余量）
        for (int ring = 0; ring <= 4; ring++) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (WeatherData w : frame) {
                int ix = (int) Math.round((w.lon() - 115.840) / 0.030);
                int iy = (int) Math.round((w.lat() - 28.640) / 0.030);
                if (ix + iy == ring) {
                    min = Math.min(min, w.windSpeedMs());
                    max = Math.max(max, w.windSpeedMs());
                }
            }
            assertTrue(max - min <= 0.45,
                    "第 " + ring + " 圈内两点风差应只由噪声决定（≤0.4），实际 " + (max - min));
        }
    }

    @Test
    @DisplayName("时间波动同步：整片风场一起涨落，九点的帧均值随相位起伏")
    void wholeFieldOscillatesTogether() {
        publisher.clear();
        Set<Double> frameMeans = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            publisher.clear();
            sim.pollOnce(i * 1_000L);
            double sum = 0.0;
            for (WeatherData w : publisher.weather()) {
                sum += w.windSpeedMs();
            }
            frameMeans.add(Math.round(sum / 9.0 * 100.0) / 100.0);
        }
        assertTrue(frameMeans.size() > 3,
                "帧均值应随相位起伏（共享相位项），实际只有 " + frameMeans.size() + " 种取值");
    }

    @Test
    @DisplayName("相邻格点风向差随格距递增，且整片风向同向变化")
    void directionAlsoGradient() {
        publisher.clear();
        sim.pollOnce(1_000L);
        List<WeatherData> frame = publisher.weather();

        double d0 = Double.NaN;
        double d4 = Double.NaN;
        for (WeatherData w : frame) {
            int ix = (int) Math.round((w.lon() - 115.840) / 0.030);
            int iy = (int) Math.round((w.lat() - 28.640) / 0.030);
            if (ix + iy == 0) {
                d0 = w.windDirDeg();
            } else if (ix + iy == 4) {
                d4 = w.windDirDeg();
            }
        }
        assertNotNull(d0);
        assertNotNull(d4);
        assertEquals(1.5 * 4, d4 - d0, 0.2, "东北角风向应比西南角大 6°（4 格 × 1.5°）");
    }
}
