package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引擎侧残差出口（11-03 D1）的行为验证。
 *
 * <h3>为什么用「南北向直线中心线」做夹具</h3>
 * <p>残差的分量是曲线坐标 {@code (s, e)}，{@code s} 沿参考线、{@code e} 垂直于它。
 * 取一条正北向的直线后，{@code s} 就等价于「纬度方向位移」、{@code e} 等价于「经度方向位移」，
 * 构造与断言都不需要再做旋转——<b>把夹具的几何复杂度降到零，断言才能只盯残差本身</b>。
 * 换个斜向中心线，一旦断言失败就得先排查是旋转算错了还是残差算错了。
 *
 * <h3>这条链的语义（断言都围绕它）</h3>
 * <ul>
 *   <li>不是每拍都产样本：每 {@value PrecisePredictionEngine#PROBE_INTERVAL_SEC} s 撒一支探针，
 *       探针在 {@code t0 + τ} 才评一次，τ 按
 *       {@code PROBE_LAG_GRID_SEC} 轮转（否则 τ 恒为 1 s，σ 的增长项永远没人检验）；</li>
 *   <li>残差 = 实测 {@code (s, e)} − <b>探针那一版</b>预测在同一时刻内插出的 {@code (s, e)}；</li>
 *   <li>带符号：{@code errAlong > 0} 船比预测跑得快，{@code errCross > 0} 实测偏航道右侧；</li>
 *   <li>{@code predVersion} 记的是<b>探针那一版</b>的版本号（不是评估这拍的版本）；</li>
 *   <li>{@code residualSamplesOf} 纯读，可重复调用且内容一致。</li>
 * </ul>
 */
class PrecisePredictionEngineResidualTest {

    private static final String MMSI = "413000001";

    private static final PredictionSettings SETTINGS =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    /** 正北向直线：{@code s} = 北向位移，{@code e} = 东向位移。 */
    private static final WaterwayGeometry CENTERLINE = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(116.0, 28.0),
            new WaterwayGeometry.Vertex(116.0, 28.5)));

    private static final double ORIGIN_LON = 116.0;
    private static final double ORIGIN_LAT = 28.1;
    private static final long T0 = 1_760_000_000_000L;

    /** 8 节 ≈ 4.115 m/s：每秒走这么远。 */
    private static final double STEP_M = 4.115;

    private static PrecisePredictionEngine engine() {
        return new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE);
    }

    @Test
    @DisplayName("首拍没有可评估的探针，残差应为空（不是异常、也不是零残差样本）")
    void firstTick_shouldProduceNoResidual() {
        PrecisePredictionEngine engine = engine();

        engine.recordVersion(MMSI, 1L);
        engine.predict(observation(ORIGIN_LON, ORIGIN_LAT, T0));

        assertTrue(engine.residualSamplesOf(MMSI).isEmpty(),
                "刚撒下的探针要到 t0+τ 才评，此刻没有「已到期」的预测可配对");
    }

    @Test
    @DisplayName("探针到点才产样本：τ=60、版本取探针那版、σ 与船速都对得上")
    void probeMatures_shouldPairWithThatVersion() {
        PrecisePredictionEngine engine = engine();

        // 匀速直航 1 分钟；第一支探针 τ=60，恰好在第 60 s 到点
        feedStraight(engine, 60, 4.115);

        List<ResidualSample> samples = engine.residualSamplesOf(MMSI);
        assertEquals(1, samples.size(), "一分钟内只有第一支探针到点（后续探针 τ 更大）");

        ResidualSample sample = samples.get(0);
        assertEquals(60, sample.tauSec(), "第一支探针的评估时距是栅格首档 60 s");
        assertEquals(1L, sample.predVersion(), "版本号取被评估那一版（第 1 版），不是当前拍的版本");
        assertTrue(Math.abs(sample.errAlongM()) < 12.0,
                "预测与实测都按 8 节走，纵向残差应只剩滤波噪声量级，实际=" + sample.errAlongM());
        assertTrue(Math.abs(sample.errCrossM()) < 5.0,
                "没有横向位移，横向残差应接近 0，实际=" + sample.errCrossM());
        assertTrue(sample.sigmaAlongM() > 0.0 && sample.sigmaCrossM() > 0.0,
                "σ 必须是正的标准差（NEES 的分母），实际=" + sample.sigmaAlongM()
                        + "/" + sample.sigmaCrossM());
        assertTrue(sample.sigmaAlongM() > 20.0,
                "τ=60 s 处的 σ∥ 应明显大于初值（20 m 起、按 α 增长），实际=" + sample.sigmaAlongM());
        assertTrue(Math.abs(sample.vAlongMs() - STEP_M) < 0.5,
                "后验纵向速度应接近 8 节≈4.1 m/s，实际=" + sample.vAlongMs());
        assertNotNull(sample.errCourseDeg(), "给了有效实测航向就必须有航向残差");
    }

    @Test
    @DisplayName("残差带符号：跑在前面为正、偏右为正、航向偏右为正")
    void residuals_shouldCarrySign() {
        PrecisePredictionEngine engine = engine();

        // 先匀速直航 59 s，把状态喂稳
        feedStraight(engine, 59, 4.115);

        // 第 60 s：在「继续匀速」的位置上多跑 50 m、偏右 10 m、船首右偏 5°
        double[] expected = offsetM(ORIGIN_LON, ORIGIN_LAT, STEP_M * 60 + 50.0, 10.0);
        engine.recordVersion(MMSI, 61L);
        engine.predict(observation(expected[0], expected[1], 5.0, T0 + 60_000L));

        List<ResidualSample> samples = engine.residualSamplesOf(MMSI);
        assertEquals(1, samples.size());
        ResidualSample sample = samples.get(0);

        assertEquals(50.0, sample.errAlongM(), 15.0,
                "多跑 50 m ⇒ 纵向残差应为正（实测在预测之前），实际=" + sample.errAlongM());
        assertEquals(10.0, sample.errCrossM(), 5.0,
                "偏航道右侧 10 m ⇒ 横向残差应为正，实际=" + sample.errCrossM());
        assertEquals(5.0, sample.errCourseDeg(), 4.0,
                "船首右偏 5° ⇒ 航向残差应为正（最短弧），实际=" + sample.errCourseDeg());
    }

    @Test
    @DisplayName("τ 在栅格上轮转：连跑 400 s 应得到 60/180/300 三档，而不是清一色 60")
    void probeLags_shouldRotate() {
        PrecisePredictionEngine engine = engine();

        feedStraight(engine, 400, 4.115);

        List<ResidualSample> samples = engine.residualSamplesOf(MMSI);
        assertEquals(3, samples.size(),
                "400 s 内到点的探针只有三支（60、210、360 三个评估时刻），实际=" + samples.size());
        assertEquals(60, samples.get(0).tauSec());
        assertEquals(180, samples.get(1).tauSec(), "第二支探针按栅格取 180，不该还是 60");
        assertEquals(300, samples.get(2).tauSec(), "第三支取 300");
    }

    @Test
    @DisplayName("样本按 sampleTs 升序；residualSamplesOf 可重复调用且内容一致")
    void samples_shouldBeAscendingAndReadOnly() {
        PrecisePredictionEngine engine = engine();

        feedStraight(engine, 400, 4.115);

        List<ResidualSample> first = engine.residualSamplesOf(MMSI);
        List<ResidualSample> second = engine.residualSamplesOf(MMSI);

        assertEquals(first, second, "重复索取必须返回同一份内容（幂等可重入）");
        for (int index = 1; index < first.size(); index++) {
            assertTrue(first.get(index).sampleTsMs() > first.get(index - 1).sampleTsMs(),
                    "样本时刻必须严格递增，第 " + index + " 条乱了");
        }
    }

    @Test
    @DisplayName("未跟踪船、空 mmsi 都返回空列表，不抛异常")
    void unknownShip_shouldReturnEmptyList() {
        PrecisePredictionEngine engine = engine();
        engine.recordVersion(MMSI, 1L);
        engine.predict(observation(ORIGIN_LON, ORIGIN_LAT, T0));

        assertTrue(engine.residualSamplesOf("999999999").isEmpty(), "未跟踪的船没有残差");
        assertTrue(engine.residualSamplesOf(null).isEmpty(), "null 不许抛 NPE");
        assertTrue(engine.residualSamplesOf("  ").isEmpty(), "空白 mmsi 视同无效");
    }

    @Test
    @DisplayName("版本号只增不减：回退的登记不得生效")
    void recordVersion_shouldNeverGoBackwards() {
        PrecisePredictionEngine engine = engine();

        engine.recordVersion(MMSI, 7L);
        engine.predict(observation(ORIGIN_LON, ORIGIN_LAT, T0));
        engine.recordVersion(MMSI, 3L);
        for (int second = 1; second <= 60; second++) {
            double[] point = offsetM(ORIGIN_LON, ORIGIN_LAT, STEP_M * second, 0.0);
            engine.predict(observation(point[0], point[1], T0 + second * 1_000L));
        }

        assertEquals(7L, engine.residualSamplesOf(MMSI).get(0).predVersion(),
                "探针记的是撒点那一刻的版本 7；之后回退的 3 不得生效");
    }

    @Test
    @DisplayName("超出预测时域的观测不产残差（预测压根没覆盖那一刻）")
    void observationBeyondHorizon_shouldProduceNoResidual() {
        PredictionSettings shortHorizon =
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 120L, 60L, 900L);
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                shortHorizon, PrecisePredictionEngine.Params.defaults(), CENTERLINE);

        engine.recordVersion(MMSI, 1L);
        engine.predict(observation(ORIGIN_LON, ORIGIN_LAT, T0));

        // 时域只有 120 s，观测直接跳到 300 s 后：探针虽已过评估时刻，但末点没覆盖到
        double[] far = offsetM(ORIGIN_LON, ORIGIN_LAT, STEP_M * 300, 0.0);
        engine.recordVersion(MMSI, 2L);
        engine.predict(observation(far[0], far[1], T0 + 300_000L));

        assertTrue(engine.residualSamplesOf(MMSI).isEmpty(),
                "τ 超出时域的观测无法内插预测位置，不能硬算一笔假残差");
    }

    // ───────────── 夹具 ─────────────

    /**
     * 沿正北直航 {@code seconds} 秒，每秒一条观测（8 节匀速）。
     *
     * @param engine  被测引擎
     * @param seconds 时长，s（含第 0 s 那条起点观测）
     */
    private static void feedStraight(PrecisePredictionEngine engine, int seconds, double metersPerSec) {
        for (int second = 0; second <= seconds; second++) {
            double[] point = offsetM(ORIGIN_LON, ORIGIN_LAT, metersPerSec * second, 0.0);
            engine.recordVersion(MMSI, second + 1L);
            engine.predict(observation(point[0], point[1], T0 + second * 1_000L));
        }
    }

    private static AisMessage observation(double lon, double lat, long ts) {
        return observation(lon, lat, 0.0, ts);
    }

    /**
     * 构造一条观测。
     *
     * @param cogDeg 对地航向，度；中心线正北，故 0 即沿航道
     */
    private static AisMessage observation(double lon, double lat, double cogDeg, long ts) {
        return new AisMessage(MMSI, "测试船", lon, lat, 8.0, cogDeg, ts);
    }

    /**
     * 从基点按「沿航道 / 横向」偏移若干米。
     *
     * <p>中心线正北，故沿航道即北向、横向即东向，无需旋转。
     *
     * @return {@code [lon, lat]}
     */
    private static double[] offsetM(double lon, double lat, double alongM, double crossM) {
        double metersPerDegLat = WaterwayGeometry.METERS_PER_DEG_LAT;
        double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(lat));
        return new double[] {
                lon + crossM / metersPerDegLon,
                lat + alongM / metersPerDegLat};
    }
}
