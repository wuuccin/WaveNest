package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 冷启动参考线走向的稳健性（M2 · 卢肄文 · 10-27 T1-8 前置缺陷回归）。
 *
 * <h3>缺陷是什么</h3>
 * <p>M1 仿真器按 1 Hz 发 AIS、8 节 ⇒ 每拍真实位移只有 4.1 m，而位置噪声是
 * {@code ±0.00005°}（每轴 ±5.5 m）——<b>单拍位移小于噪声</b>。原实现只等两拍、
 * 用这 4 m 的基线定参考线走向，等于让噪声定走向。后果不是「精度差一点」而是<b>整条预测冻住</b>：
 * <pre>
 *   走向 259°（真值 21°）→ v_s = sog·cos(21° − 259°) ≈ −2.2 m/s（负）
 *   → 弧长 s 往回走、被夹在折线端点 → 30 个预测点全挤在起点 0–17 m 内
 *   → σ 照常增长，结构断言过、地理框也过 → 静默错误：画面上船「不动了」
 * </pre>
 * 实测 200 个随机种子（改前）：走向误差中位 25.9°、P90 73.3°、最大 174.6°，
 * 其中 16 个种子（8%）的 18 min 尾巴前伸不足 1 km（即完全冻住）。
 *
 * <h3>为什么这条测试必须存在</h3>
 * <p>已有的 {@code PrecisePredictionReplayTest} 断言的是「不崩、不被拦、σ 单调」，
 * 这三条在一个「冻住的轨迹」上<b>全部成立</b>——盲区就在这里。本类补的判据是
 * 「预测尾巴必须真的前伸」与「参考线走向必须接近真值」，这是唯一能抓住该缺陷的断言。
 */
class ColdStartBearingRobustnessTest {

    private static final long T0 = 1_760_000_000_000L;

    /** 南昌航段起点（T1-4 仿真器真值）与真航向。 */
    private static final double LON0 = 115.8610;
    private static final double LAT0 = 28.6640;
    private static final double TRUE_COG_DEG = 21.0;

    private static final double SOG_KNOTS = 8.0;
    private static final double SOG_MS = SOG_KNOTS * 1852.0 / 3600.0;
    private static final double MPD_LAT = 111_320.0;

    /** 仿真 AIS 噪声：{@code wavenest.sim.ais.noise: 0.00005}，均匀分布 ±5.5 m。 */
    private static final double NOISE_DEG = 0.00005;

    private static final int SEEDS = 200;

    private static final int TICKS = 120;

    @Test
    @DisplayName("★ 确定性：两点仅 4 m 却因噪声横向拉开 20 m 时，参考线走向必须取自报航向")
    void bearingMustComeFromReportedCourseWhenSpanIsNoiseDominated() {
        PrecisePredictionEngine engine = engine();
        // 第二点被噪声推到起点正西 20 m —— 实测走向约 270°，与真航向 21° 相差 111°。
        // 若实现仍用实测走向，主轴方位会跟着噪声走。
        var outcome = engine.predict(observation("NOISY", LON0, LAT0, 0L));
        assertTrue(outcome.isEmpty(), "第 0 拍应冷启动未完成");

        outcome = engine.predict(observation("NOISY", LON0 - 0.000205, LAT0 - 0.000030, 1_000L));
        assertTrue(outcome.isPresent(), "第 2 拍应已建出参考线（冷启动时机不得被推迟）");
        assertEquals(TRUE_COG_DEG, outcome.get().courseDeg(), 0.5,
                "主轴方位应取自报航向 21°，而不是被噪声带偏的实测走向");

        // 并且尾巴必须真的前伸约 7.4 km（8 节 × 1800 s），不是冻在起点
        double advanceM = tailAdvanceM(outcome.get());
        assertTrue(advanceM > 6_000.0,
                "预测尾巴只前伸 " + Math.round(advanceM) + " m，说明 v_s 被走向带成了负值（轨迹冻住）");
    }

    @Test
    @DisplayName("★ 统计：200 个噪声种子下，走向误差 P90 < 10°、无一个种子冻住")
    void bearingMustStayRobustAcrossNoiseSeeds() {
        List<Double> axisErrors = new ArrayList<>(SEEDS);
        double minAdvanceM = Double.MAX_VALUE;

        for (long seed = 1; seed <= SEEDS; seed++) {
            PrecisePredictionEngine engine = engine();
            Random random = new Random(seed);
            PrecisePredictionEngine.Outcome last = null;

            for (int tick = 0; tick <= TICKS; tick++) {
                double east = SOG_MS * tick * Math.sin(Math.toRadians(TRUE_COG_DEG));
                double north = SOG_MS * tick * Math.cos(Math.toRadians(TRUE_COG_DEG));
                double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(LAT0));
                double lon = LON0 + east / metersPerDegLon + NOISE_DEG * (2 * random.nextDouble() - 1.0);
                double lat = LAT0 + north / MPD_LAT + NOISE_DEG * (2 * random.nextDouble() - 1.0);
                var outcome = engine.predict(observation("SEED-" + seed, lon, lat, tick * 1000L));
                if (outcome.isPresent()) {
                    last = outcome.get();
                }
            }
            assertTrue(last != null, "种子 " + seed + " 从未建出参考线");
            axisErrors.add(angleDiffDeg(last.courseDeg(), TRUE_COG_DEG));
            minAdvanceM = Math.min(minAdvanceM, tailAdvanceM(last));
        }

        Collections.sort(axisErrors);
        double p90 = axisErrors.get((int) Math.round(axisErrors.size() * 0.9) - 1);

        assertTrue(p90 < 10.0, "走向误差 P90 = " + fmt(p90) + "°，超过 10°（参考线走向被噪声主导）");
        assertTrue(axisErrors.get(axisErrors.size() - 1) < 20.0,
                "走向误差最大 " + fmt(axisErrors.get(axisErrors.size() - 1)) + "°，出现方向反转");
        assertTrue(minAdvanceM > 6_000.0,
                "最坏种子下预测尾巴只前伸 " + Math.round(minAdvanceM)
                        + " m（8 节 × 1800 s 应为约 7400 m），存在轨迹冻住");

        System.out.printf(java.util.Locale.ROOT,
                "%n[T1-8 回归] %d 个种子：走向误差 中位 %s° / P90 %s° / 最大 %s°；最小尾巴前伸 %.0f m%n",
                SEEDS, fmt(axisErrors.get(axisErrors.size() / 2)), fmt(p90),
                fmt(axisErrors.get(axisErrors.size() - 1)), minAdvanceM);
    }

    // ───────── 小工具 ─────────

    private static PrecisePredictionEngine engine() {
        PredictionSettings settings =
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);
        return new PrecisePredictionEngine(settings, PrecisePredictionEngine.Params.defaults());
    }

    private static AisMessage observation(String mmsi, double lon, double lat, long offsetMs) {
        return new AisMessage(mmsi, "SIM-SHIP-01", lon, lat, SOG_KNOTS, TRUE_COG_DEG, T0 + offsetMs);
    }

    /** 首末点平面距离，m。 */
    private static double tailAdvanceM(PrecisePredictionEngine.Outcome outcome) {
        var head = outcome.points().get(0);
        var tail = outcome.points().get(outcome.points().size() - 1);
        double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(LAT0));
        return Math.hypot((tail.lon() - head.lon()) * metersPerDegLon,
                (tail.lat() - head.lat()) * MPD_LAT);
    }

    private static double angleDiffDeg(double fromDeg, double toDeg) {
        double raw = Math.abs(fromDeg - toDeg) % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
