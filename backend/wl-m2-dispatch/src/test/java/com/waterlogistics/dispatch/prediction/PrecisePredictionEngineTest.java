package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.PremiseStatus;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 精算引擎单测（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>对应清单「判据」里能在此层断言的三条：
 * <ol>
 *   <li>冷启动不报错、不空转（第一帧因参考线不足回落，第二帧出预测）；</li>
 *   <li>{@code sigmaAlong}／{@code sigmaCross} 有限、为正、随外推时长单调不减；</li>
 *   <li>有新观测进入时不确定度<b>收缩</b>（预测步方差 &gt; 更新后方差）。</li>
 * </ol>
 *
 * <p>坐标取赣江南昌段（115.90E／28.65N），与生产地理框一致，避免「测试能过、上线被框拦」。
 */
class PrecisePredictionEngineTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String MMSI = "413000001";
    private static final double ORIGIN_LON = 115.9000;
    private static final double ORIGIN_LAT = 28.6500;
    private static final double MPD_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;
    private static final double KNOT_TO_MS = 1852.0 / 3600.0;

    private PredictionSettings settings;

    private PrecisePredictionEngine engine;

    @BeforeEach
    void setUp() {
        // 与 PredictionConfig 从 sys_param 读出的形状同构：σ_pos=20、α∥=0.05、σ_e=15、α⊥=0.03
        settings = new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);
        engine = new PrecisePredictionEngine(settings, PrecisePredictionEngine.Params.defaults());
    }

    /** 沿 bearing 匀速直航的观测（elapsedSec 为相对 T0 的秒数）。 */
    private static AisMessage at(double bearingDeg, double speedMs, double elapsedSec) {
        double east = speedMs * elapsedSec * Math.sin(Math.toRadians(bearingDeg));
        double north = speedMs * elapsedSec * Math.cos(Math.toRadians(bearingDeg));
        double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(ORIGIN_LAT));
        return new AisMessage(MMSI, "测船",
                ORIGIN_LON + east / metersPerDegLon,
                ORIGIN_LAT + north / MPD_LAT,
                speedMs / KNOT_TO_MS,
                bearingDeg,
                T0 + Math.round(elapsedSec * 1000.0));
    }

    @Test
    @DisplayName("冷启动：首帧建不出参考线回落（空），第二帧即出预测且不报错")
    void coldStart_shouldFallbackThenPredict() {
        assertTrue(engine.predict(at(30.0, 3.0, 0.0)).isEmpty(), "一个实测点建不出折线");
        assertEquals(0, engine.stateOf(MMSI).orElseThrow().observationCount() - 1,
                "首帧仍要入窗，等第二帧");

        PrecisePredictionEngine.Outcome outcome = engine.predict(at(30.0, 3.0, 10.0)).orElseThrow();

        assertEquals(MMSI, outcome.mmsi());
        assertEquals(31, outcome.points().size(), "1800 s / 60 s + 1");
        assertEquals(PremiseStatus.VALID, outcome.premise());
        assertEquals(T0 + 10_000L, outcome.t0Ms());
        assertTrue(engine.stateOf(MMSI).orElseThrow().initialized());
    }

    @Test
    @DisplayName("★ σ 有限、为正、随外推时长单调不减")
    void sigma_shouldBePositiveAndMonotone() {
        engine.predict(at(30.0, 3.0, 0.0));
        List<EstimatedPoint> points = engine.predict(at(30.0, 3.0, 10.0)).orElseThrow().points();

        double previousAlong = Double.NEGATIVE_INFINITY;
        double previousCross = Double.NEGATIVE_INFINITY;
        for (EstimatedPoint point : points) {
            assertTrue(Double.isFinite(point.sigmaAlongM()) && Double.isFinite(point.sigmaCrossM()));
            assertTrue(point.sigmaAlongM() > 0.0 && point.sigmaCrossM() > 0.0,
                    "σ 必须为正：" + point.sigmaAlongM() + "/" + point.sigmaCrossM());
            assertTrue(point.sigmaAlongM() >= previousAlong - 1e-9, "σ∥ 不得下降");
            assertTrue(point.sigmaCrossM() >= previousCross - 1e-9, "σ⊥ 不得下降");
            previousAlong = point.sigmaAlongM();
            previousCross = point.sigmaCrossM();
        }
        // τ=0 的 σ∥ 是后验 sqrt(P_ss)，明显小于初始化时的 σ_pos=20 m（已被一次观测更新过）
        assertTrue(points.get(0).sigmaAlongM() < 20.0,
                "τ=0 的 σ∥ 应已被观测压低：" + points.get(0).sigmaAlongM());
        // τ 增大时必然增长：末点 σ∥ 至少多出 α∥·1800 = 90 m
        assertTrue(points.get(points.size() - 1).sigmaAlongM() > points.get(0).sigmaAlongM() + 89.0);
    }

    @Test
    @DisplayName("★ 有新观测进入时不确定度收缩：预测步方差 > 更新后方差")
    void observation_shouldShrinkUncertainty() {
        engine.predict(at(30.0, 3.0, 0.0));
        engine.predict(at(30.0, 3.0, 10.0));

        PrecisePredictionEngine.StateView tight = engine.stateOf(MMSI).orElseThrow();
        assertTrue(tight.predictedVarianceS() > tight.posteriorVarianceS(),
                "更新必须压低方差：预测 " + tight.predictedVarianceS()
                        + " vs 更新后 " + tight.posteriorVarianceS());

        // 断流 300 s：先大幅放大预测方差，再由一条新观测把它压回去
        engine.predict(at(30.0, 3.0, 310.0));
        PrecisePredictionEngine.StateView afterGap = engine.stateOf(MMSI).orElseThrow();
        System.out.printf(java.util.Locale.ROOT,
                "%n[F4] 断流 300 s 前后：预测方差 %.1f → %.1f m²，更新后 %.1f → %.1f m²（σ∥ %.2f → %.2f m）%n",
                tight.predictedVarianceS(), afterGap.predictedVarianceS(),
                tight.posteriorVarianceS(), afterGap.posteriorVarianceS(),
                Math.sqrt(tight.posteriorVarianceS()), Math.sqrt(afterGap.posteriorVarianceS()));
        assertTrue(afterGap.predictedVarianceS() > tight.predictedVarianceS() * 2.0,
                "断流后预测方差应明显放大：" + afterGap.predictedVarianceS());
        assertTrue(afterGap.posteriorVarianceS() < afterGap.predictedVarianceS(),
                "新观测必须让不确定度收缩：预测 " + afterGap.predictedVarianceS()
                        + " → 更新后 " + afterGap.posteriorVarianceS());
    }

    @Test
    @DisplayName("观测时标不前进（迟到/重复投递）时不推进状态，只按当前状态重算")
    void lateObservation_shouldNotAdvanceState() {
        engine.predict(at(30.0, 3.0, 0.0));
        PrecisePredictionEngine.Outcome first = engine.predict(at(30.0, 3.0, 10.0)).orElseThrow();
        double beforeVariance = engine.stateOf(MMSI).orElseThrow().posteriorVarianceS();

        // 同一时刻重复投递（F3 重算就复用同一条观测）：状态不动，但仍要给出一版预测
        PrecisePredictionEngine.Outcome again = engine.predict(at(30.0, 3.0, 10.0)).orElseThrow();

        assertEquals(beforeVariance, engine.stateOf(MMSI).orElseThrow().posteriorVarianceS(), 1e-12,
                "重复观测不得推进滤波器");
        assertEquals(first.points(), again.points(), "同一状态重算的点列应完全一致");
        assertEquals(first.t0Ms(), again.t0Ms(), "时间锚取已推进到的时刻，不得倒退");
    }

    @Test
    @DisplayName("转弯船：点位仍然有限、落在起点附近（参考线延伸足够，不撞端点）")
    void turningShip_shouldStayFinite() {
        engine.predict(at(30.0, 3.0, 0.0));
        engine.predict(at(30.0, 3.0, 10.0));
        PrecisePredictionEngine.Outcome outcome = engine.predict(at(40.0, 3.0, 20.0)).orElseThrow();

        for (EstimatedPoint point : outcome.points()) {
            assertTrue(Double.isFinite(point.lon()) && Double.isFinite(point.lat()));
            // 3 m/s × 1800 s ≈ 5.4 km，取 20 km 上限抓「点列飞出去」
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(ORIGIN_LAT));
            double dx = (point.lon() - ORIGIN_LON) * metersPerDegLon;
            double dy = (point.lat() - ORIGIN_LAT) * MPD_LAT;
            assertTrue(Math.hypot(dx, dy) < 20_000.0, "点位飞太远：" + point.lon() + "," + point.lat());
        }
        assertFalse(outcome.zSeries().isEmpty(), "至少积累了一次归一化新息");
        assertTrue(outcome.zSeries().size() <= PrecisePredictionEngine.Z_SERIES_CAP);
    }

    @Test
    @DisplayName("有效期由带宽超限时刻给出，且不超过时域")
    void validity_shouldComeFromBandwidth() {
        engine.predict(at(30.0, 3.0, 0.0));
        PrecisePredictionEngine.Outcome outcome = engine.predict(at(30.0, 3.0, 10.0)).orElseThrow();

        assertTrue(outcome.validitySec() > 0L && outcome.validitySec() <= settings.horizonSec(),
                "有效期 " + outcome.validitySec() + " s 应落在 (0, " + settings.horizonSec() + "]");
    }

    @Test
    @DisplayName("越界观测与匀速版同一口径：抛 IllegalArgumentException")
    void invalidObservation_shouldThrow() {
        AisMessage bad = new AisMessage(MMSI, "测船", ORIGIN_LON, 100.0, 5.0, 30.0, T0);
        assertThrows(IllegalArgumentException.class, () -> engine.predict(bad));
        assertEquals(0, engine.trackedShipCount(), "脏数据不得建出状态");
    }

    @Test
    @DisplayName("模式解析：认不出就回落匀速版，不抛异常")
    void modeParsing_shouldFallBackToConstant() {
        assertEquals(PredictionMode.PRECISE, PredictionMode.fromCode("precise"));
        assertEquals(PredictionMode.PRECISE, PredictionMode.fromCode(" PRECISE "));
        assertEquals(PredictionMode.CONSTANT, PredictionMode.fromCode("constant"));
        assertEquals(PredictionMode.CONSTANT, PredictionMode.fromCode("精算"));
        assertEquals(PredictionMode.CONSTANT, PredictionMode.fromCode(null));
    }
}
