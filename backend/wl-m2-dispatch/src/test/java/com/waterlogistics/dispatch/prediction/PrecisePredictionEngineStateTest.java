package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 精算引擎的<b>状态生命周期</b>单测（11-14 序号 2-6 · MEDIUM.41 / MEDIUM.3）。
 *
 * <p>这里验的两件事都属于「不报错、只是慢慢变坏」的那一类：
 * <ul>
 *   <li>{@link PrecisePredictionEngine} 尾部重锚后是否还认得这艘船的状态（M41）；</li>
 *   <li>{@code states} 是否可清、是否有上限（M3，此前只增不删）。</li>
 * </ul>
 *
 * <p>为什么把两件事放一起：它们的失败表现同一个——「跑久了精度掉、内存涨」，
 * 而单看某一拍的数据都正常。
 */
class PrecisePredictionEngineStateTest {

    private static final double LAT0 = 30.5000;
    private static final double LON0 = 121.4700;

    /** 8 kn ≈ 4.115 m/s，正北航行。 */
    private static final double SOG_KN = 8.0;

    private static final double SPEED_MS = SOG_KN * 1852.0 / 3600.0;

    /** 1 Hz，第 n 秒的观测（正北方向推进 {@link #SPEED_MS} 米每秒）。 */
    private static AisMessage northward(String mmsi, int second) {
        double northM = SPEED_MS * second;
        return new AisMessage(mmsi, "测试船", LON0,
                LAT0 + northM / WaterwayGeometry.METERS_PER_DEG_LAT, SOG_KN, 0.0,
                1_794_268_800_000L + second * 1000L);
    }

    /**
     * 与产线同口径的引擎：无中心线（走「实测窗口建参考线」的退化分支，
     * 只有这条分支才有尾部重锚），时域压到 60 s 让延伸段短、重锚来得快。
     */
    private static PrecisePredictionEngine engine() {
        PredictionSettings settings = new PredictionSettings(20.0, 0.05, 15.0, 0.03, 60L, 60L, 900L);
        PrecisePredictionEngine.Params params = new PrecisePredictionEngine.Params(
                50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0);
        return new PrecisePredictionEngine(settings, params);
    }

    @Test
    @DisplayName("★ MEDIUM.41：跨越参考线尾部重锚后仍出预测，位置不倒退、协方差不被重置")
    void reanchor_shouldKeepFilterState() {
        PrecisePredictionEngine engine = engine();
        String mmsi = "413140001";

        double previousNorthM = Double.NaN;
        double lastNorthM = Double.NaN;
        boolean sawPrediction = false;
        int advanced = 0;
        // 1 Hz 走 20 分钟：延伸段约 860 m，足够跨越尾部数次
        for (int second = 1; second <= 1200; second++) {
            Optional<PrecisePredictionEngine.Outcome> outcome = engine.predict(northward(mmsi, second));
            if (outcome.isEmpty()) {
                continue;
            }
            sawPrediction = true;
            double northM = (outcome.get().points().get(0).lat() - LAT0)
                    * WaterwayGeometry.METERS_PER_DEG_LAT;

            // 观测在持续北行，首个预测点只能往北走。若重锚把状态打回初始化再重放窗口，
            // 位置会在重锚帧跳一下；平移写法下只换坐标原点，物理位置连续。
            if (!Double.isNaN(previousNorthM)) {
                assertTrue(northM >= previousNorthM - 0.5,
                        "第 " + second + " 秒位置倒退：" + previousNorthM + " → " + northM);
                if (northM > previousNorthM) {
                    advanced++;
                }
            }
            previousNorthM = northM;
            lastNorthM = northM;

            PrecisePredictionEngine.StateView view = engine.stateOf(mmsi).orElseThrow();
            assertTrue(view.initialized(), "重锚不得把滤波器打回未初始化状态");
            // σ_pos = 20 m ⇒ 初始化协方差 400 m²。连续跟踪且重锚不重置，
            // 后验方差必须始终明显小于初值。
            assertTrue(view.posteriorVarianceS() < 400.0,
                    "第 " + second + " 秒后验方差回到了初始化量级：" + view.posteriorVarianceS());
        }

        assertTrue(sawPrediction, "1200 拍里必须出过预测，否则本用例什么都没验到");
        assertTrue(advanced > 1000, "位置应逐拍推进，实际推进帧数=" + advanced);
        // 1200 s × 4.115 m/s ≈ 4938 m；只要不是被拉回原地，末帧必须走完相当一段
        assertTrue(lastNorthM > 4_000.0, "末帧位置只到 " + lastNorthM + " m，像是被重锚拽回去了");
        assertEquals(1, engine.trackedShipCount(), "只喂了一艘船");
    }

    @Test
    @DisplayName("★ MEDIUM.3：单船状态可清；按静默时长清理会连引擎侧一起清掉")
    void evict_shouldRemoveShipState() {
        PrecisePredictionEngine engine = engine();
        for (int second = 1; second <= 120; second++) {
            engine.predict(northward("413140002", second));
            engine.predict(northward("413140003", second));
        }
        assertEquals(2, engine.trackedShipCount());

        assertTrue(engine.evict("413140002"), "存在过的船应能清掉");
        assertFalse(engine.evict("413140002"), "重复清理返回 false，不报错");
        assertEquals(1, engine.trackedShipCount());
        assertTrue(engine.stateOf("413140002").isEmpty(), "状态真的不在了，不是只摘了个计数");

        // 静默超时：最后一条观测在第 120 秒，往后推 10 分钟全部过期
        long nowMs = 1_794_268_800_000L + 120_000L + 600_000L;
        assertEquals(1, engine.evictStale(nowMs, 300_000L), "只剩那一艘，应清掉 1 艘");
        assertEquals(0, engine.trackedShipCount());
    }

    @Test
    @DisplayName("★ MEDIUM.3：船数超上限时按最后观测时刻淘汰，表不会无界增长")
    void trackedShips_shouldBeBounded() {
        PrecisePredictionEngine engine = engine();

        int over = PrecisePredictionEngine.MAX_TRACKED_SHIPS + 8;
        for (int index = 0; index < over; index++) {
            // 每艘船给一个递增的时刻：淘汰按「最后观测时刻」排序，时刻必须可比，
            // 否则同一毫秒的船之间谁先被淘汰是任意的，断言就变成碰运气
            engine.predict(northward("4131" + String.format("%05d", index), index + 1));
        }

        assertEquals(PrecisePredictionEngine.MAX_TRACKED_SHIPS, engine.trackedShipCount(),
                "超上限后必须淘汰到上限，而不是每来一艘新船就多吃一份内存");
        // 淘汰的是「最后观测时刻最旧」的：先来的那几艘应已不在
        assertTrue(engine.stateOf("413100000").isEmpty(), "最早出现的船应被淘汰");
        assertTrue(engine.stateOf("4131" + String.format("%05d", over - 1)).isPresent(),
                "最后出现的船必须还在");
    }
}
