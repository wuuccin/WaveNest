package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MultiStepPredictor} 单测（A 组 · {@code 80} §3.2.7 步骤 E）。
 *
 * <p>验证点：常速推进与上行船带号、限速衰减的<b>解析积分</b>（含上行保号）、协方差闭式与
 * 逐拍 {@code F P Fᵀ + Q} 等价、{@code α·τ} 模型误差项、横向截断、反投影的偏侧方向、
 * 有效期复算出限值（与附录 B 末注的 1020 s 对齐）、越界夹紧不抛异常。
 */
class MultiStepPredictorTest {

    /** 一条正北向直线航道，纬度 28.60 → 28.70（约 11132 m）。s 与纬度线性对应，便于断言。 */
    private static final double BASE_LAT = 28.60;
    private static final double MPD_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;
    private static final double LON = 115.85;
    private static final WaterwayGeometry NORTH = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(LON, BASE_LAT),
            new WaterwayGeometry.Vertex(LON, BASE_LAT + 0.10)));

    /** 全线长度直接取几何对象的累计弧长（不要另行用 {@code 0.10 * MPD_LAT} 复算：
     *  {@code 28.70 − 28.60} 的浮点差与 {@code 0.10} 并不严格相等，会比几何内部长度差 1e-10 量级）。 */
    private static final double TOTAL_M = NORTH.totalLengthM();

    private static final long T0_MS = 1_700_000_000_000L;

    private static MultiStepPredictor.Params params(long stepSec, long horizonSec,
            double alphaAlongMs, double alphaCrossMs) {
        return new MultiStepPredictor.Params(MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, 60.0,
                alphaAlongMs, alphaCrossMs, 50.0, 180.0, 40.0, stepSec, horizonSec);
    }

    /** 横向完全静止（无偏移、无方差增长）——把 {@code σ_cross} 的干扰从纵向断言里剔掉。 */
    private static MultiStepPredictor.LateralForecast stillLateral() {
        return MultiStepPredictor.LateralForecast.ou(0.0, 0.0, 0.0, 120.0, 15.0);
    }

    private static MultiStepPredictor.Input input(double s0, double v0, double pSS, double pSV,
            double pVV, MultiStepPredictor.LateralForecast lateral,
            MultiStepPredictor.SpeedLimitLookup speedLimit) {
        return new MultiStepPredictor.Input(T0_MS, s0, v0, pSS, pSV, pVV, lateral, NORTH, speedLimit);
    }

    private static List<MultiStepPredictor.Sample> run(double s0, double v0,
            MultiStepPredictor.Params params) {
        return MultiStepPredictor.predict(
                input(s0, v0, 400.0, 0.0, 0.0, stillLateral(),
                        MultiStepPredictor.SpeedLimitLookup.unbounded()),
                params);
    }

    @Test
    @DisplayName("常速无衰减：s(τ) = s₀ + v·τ，点数 = horizon/step + 1")
    void constantSpeed_shouldAdvanceLinearly() {
        MultiStepPredictor.Params params = params(60, 180, 0.0, 0.0);
        List<MultiStepPredictor.Sample> samples = run(1000.0, 3.0, params);

        assertEquals(params.pointCount(), samples.size());
        assertEquals(4, samples.size());
        for (MultiStepPredictor.Sample sample : samples) {
            assertEquals(1000.0 + 3.0 * sample.tauSec(), sample.s(), 1e-9,
                    "τ=" + sample.tauSec() + " 的弧长不对");
        }
        // 反投影：正北航道、无横向偏移 → 经度不变、纬度随 s 增加
        MultiStepPredictor.Sample last = samples.get(samples.size() - 1);
        assertEquals(LON, last.lon(), 1e-12);
        assertEquals(BASE_LAT + (1000.0 + 540.0) / MPD_LAT, last.lat(), 1e-9);
        assertEquals(0.0, last.courseDeg(), 1e-9, "正北航道走向应为 0°");
    }

    @Test
    @DisplayName("上行船 v_s < 0：s 递减、经度不变——不判异常、不往反方向拉")
    void upboundShip_shouldDecreaseS() {
        MultiStepPredictor.Params params = params(60, 180, 0.0, 0.0);
        List<MultiStepPredictor.Sample> samples = run(1000.0, -3.0, params);

        for (MultiStepPredictor.Sample sample : samples) {
            assertEquals(1000.0 - 3.0 * sample.tauSec(), sample.s(), 1e-9);
            assertEquals(LON, sample.lon(), 1e-12);
        }
    }

    @Test
    @DisplayName("上行船超速衰减：限速取负号，s 按解析积分递减（不被拉成正值）")
    void upboundSpeedDecay_shouldKeepNegativeSign() {
        MultiStepPredictor.Params params = params(60, 180, 0.0, 0.0);
        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(
                input(5000.0, -5.0, 400.0, 0.0, 0.0, stillLateral(),
                        MultiStepPredictor.SpeedLimitLookup.constant(2.0)),
                params);

        // v(τ) = −2 − 3·e^{−τ/60} ⇒ s(τ) = 5000 − [2τ + 3·60·(1 − e^{−τ/60})]
        for (MultiStepPredictor.Sample sample : samples) {
            double tau = sample.tauSec();
            double expected = 5000.0 - (2.0 * tau + 180.0 * (1.0 - Math.exp(-tau / 60.0)));
            assertEquals(expected, sample.s(), 1e-6, "τ=" + tau + " 的衰减解析积分不对");
        }
        // 上行：s 必须单调递减，且不会跳到限速之上（衰减方向正确）
        assertTrue(samples.get(1).s() < samples.get(0).s());
        assertTrue(samples.get(3).s() < samples.get(2).s());
    }

    @Test
    @DisplayName("协方差闭式与逐拍 F P Fᵀ + Q 完全等价")
    void covarianceClosedForm_shouldMatchIteratedPropagation() {
        double pSS = 100.0;
        double pSV = 5.0;
        double pVV = 0.25;
        double q = MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q;
        MultiStepPredictor.Params params = params(1, 180, 0.0, 0.0);
        MultiStepPredictor.Input in = input(0.0, 2.0, pSS, pSV, pVV, stillLateral(),
                MultiStepPredictor.SpeedLimitLookup.unbounded());

        // 逐拍递推（步长 1 s，180 拍）
        double ss = pSS;
        double sv = pSV;
        double vv = pVV;
        for (int i = 1; i <= 180; i++) {
            double nextSS = ss + 2.0 * sv + vv + q / 3.0;
            double nextSV = sv + vv + q / 2.0;
            double nextVV = vv + q;
            ss = nextSS;
            sv = nextSV;
            vv = nextVV;
        }

        assertEquals(ss, MultiStepPredictor.covarianceSS(in, params, 180.0), 1e-9);
    }

    @Test
    @DisplayName("σ_along(τ) = sqrt(P⁻_ss(τ)) + α_along·τ：α 项是代数和，不是平方和")
    void sigmaAlong_includesAlphaTermAdditively() {
        double alphaAlong = 0.15;
        MultiStepPredictor.Params params = params(60, 180, alphaAlong, 0.0);
        MultiStepPredictor.Input in = input(0.0, 2.0, 400.0, 0.0, 0.0, stillLateral(),
                MultiStepPredictor.SpeedLimitLookup.unbounded());
        MultiStepPredictor.Sample last = MultiStepPredictor.predict(in, params).get(3);

        double expected = Math.sqrt(MultiStepPredictor.covarianceSS(in, params, 180.0))
                + alphaAlong * 180.0;
        assertEquals(expected, last.sigmaAlongM(), 1e-9);

        // 代数和严格大于平方和，口径不能写反
        double quadratic = Math.hypot(Math.sqrt(MultiStepPredictor.covarianceSS(in, params, 180.0)),
                alphaAlong * 180.0);
        assertTrue(last.sigmaAlongM() > quadratic);
    }

    @Test
    @DisplayName("σ_cross 截断到航道半宽")
    void sigmaCross_truncatedAtLaneHalfWidth() {
        MultiStepPredictor.Params params = params(60, 1800, 0.0, 0.1);
        MultiStepPredictor.Input in = input(0.0, 2.0, 400.0, 0.0, 0.0,
                MultiStepPredictor.LateralForecast.ou(0.0, 0.0, 0.0, 120.0, 15.0),
                MultiStepPredictor.SpeedLimitLookup.unbounded());
        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(in, params);

        assertEquals(50.0, samples.get(samples.size() - 1).sigmaCrossM(), 1e-12,
                "横向 σ 必须截断到 laneHalfWidthM");
        for (MultiStepPredictor.Sample sample : samples) {
            assertTrue(sample.sigmaCrossM() <= 50.0 + 1e-12);
        }
    }

    @Test
    @DisplayName("反投影：e > 0 落在前进方向右侧（正北航道 → 东侧，经度更大）")
    void lateralOffset_shouldBeToTheRightOfTravel() {
        MultiStepPredictor.Params params = params(60, 60, 0.0, 0.0);
        MultiStepPredictor.Input in = input(1000.0, 2.0, 400.0, 0.0, 0.0,
                MultiStepPredictor.LateralForecast.ou(50.0, 50.0, 0.0, 120.0, 15.0),
                MultiStepPredictor.SpeedLimitLookup.unbounded());
        MultiStepPredictor.Sample last = MultiStepPredictor.predict(in, params).get(1);

        // 反投影：e = +50 必须落在航道右侧（正北航道 → 东侧）。按米核对，避免 1/cos(纬度)
        // 的参考纬度取值不同带来的 1e-8 度量级差（几何内部用段中点纬度，测试若用起点纬度就会差一点）
        double offsetEastM = (last.lon() - LON) * MPD_LAT * Math.cos(Math.toRadians(last.lat()));
        assertEquals(50.0, offsetEastM, 0.01);
        assertTrue(last.lon() > LON, "e > 0 必须落在航道右侧（东侧）");
        assertEquals(BASE_LAT + 1120.0 / MPD_LAT, last.lat(), 1e-6, "横向偏移不应改变沿程位置");
    }

    @Test
    @DisplayName("有效期复算：α_along=0.15、lTol=180 时首次超限在 τ=1020 s（与附录 B 末注一致）")
    void validity_shouldReproduceAppendixBNumber() {
        MultiStepPredictor.Params params = params(60, 1800, 0.15, 0.02);
        MultiStepPredictor.Input in = input(1000.0, 3.0, 400.0, 0.0, 0.0,
                MultiStepPredictor.LateralForecast.ou(0.0, 0.0, 0.0, 120.0, 15.0),
                MultiStepPredictor.SpeedLimitLookup.unbounded());
        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(in, params);

        long validity = MultiStepPredictor.validitySec(samples, params);
        assertEquals(1020L, validity, "σ_along 超过 180 m 的时点与附录 B 的复算值不符");

        // 语义校验：该 τ 之前都不超限，该 τ 起超限
        for (MultiStepPredictor.Sample sample : samples) {
            boolean over = sample.sigmaAlongM() > 180.0 || sample.sigmaCrossM() > 40.0;
            assertEquals(sample.tauSec() >= validity, over,
                    "τ=" + sample.tauSec() + " 的超限判定与 validitySec 不自洽");
        }
    }

    @Test
    @DisplayName("都不超限时 validitySec 回落到 horizonSec")
    void validity_shouldFallBackToHorizon() {
        MultiStepPredictor.Params params = params(60, 180, 0.0, 0.0);
        List<MultiStepPredictor.Sample> samples = run(1000.0, 3.0, params);
        assertEquals(180L, MultiStepPredictor.validitySec(samples, params));
    }

    @Test
    @DisplayName("停车（v=0）：s 不推进，但 σ 仍随时距增长")
    void zeroVelocity_shouldNotAdvanceButSigmaGrows() {
        MultiStepPredictor.Params params = params(60, 180, 0.15, 0.0);
        List<MultiStepPredictor.Sample> samples = run(1000.0, 0.0, params);

        for (MultiStepPredictor.Sample sample : samples) {
            assertEquals(1000.0, sample.s(), 1e-12);
        }
        assertTrue(samples.get(3).sigmaAlongM() > samples.get(0).sigmaAlongM());
    }

    @Test
    @DisplayName("越过中心线端点：夹紧在端点、不抛异常（常驻层不能因一艘船越界中断整轮）")
    void beyondWaterwayEnd_shouldClampWithoutThrowing() {
        MultiStepPredictor.Params params = params(60, 600, 0.0, 0.0);
        List<MultiStepPredictor.Sample> samples = run(TOTAL_M - 100.0, 3.0, params);

        assertEquals(TOTAL_M, samples.get(samples.size() - 1).s(), 1e-9);
        for (MultiStepPredictor.Sample sample : samples) {
            assertTrue(sample.s() >= 0.0 && sample.s() <= TOTAL_M);
        }
    }

    @Test
    @DisplayName("参数与输入校验：τ 分桶、非正 q、步长大于时域都要拒绝")
    void invalidArguments_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiStepPredictor.Params(0.0, 60.0, 0.15, 0.02, 50.0, 180.0, 40.0, 60L, 1800L));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiStepPredictor.Params(1e-6, 60.0, 0.15, 0.02, 50.0, 180.0, 40.0, 600L, 60L));
        assertThrows(IllegalArgumentException.class,
                () -> MultiStepPredictor.predict(
                        new MultiStepPredictor.Input(T0_MS, 0.0, 1.0, -1.0, 0.0, 0.0,
                                stillLateral(), NORTH, MultiStepPredictor.SpeedLimitLookup.unbounded()),
                        params(60, 180, 0.0, 0.0)));
    }

    @Test
    @DisplayName("PredictionParams 桥接：模块层注入的 α/horizon/step 原样进入算法参数")
    void fromPredictionParams_shouldCarryModuleLayerValues() {
        PredictionParams moduleParams = new PredictionParams(30.0, 0.2, 10.0, 0.03, 900L, 30L, 600L);
        MultiStepPredictor.Params params =
                MultiStepPredictor.Params.fromPredictionParams(moduleParams, 60.0, 200.0, 45.0);

        assertEquals(0.2, params.alphaAlongMs(), 1e-12);
        assertEquals(0.03, params.alphaCrossMs(), 1e-12);
        assertEquals(900L, params.horizonSec());
        assertEquals(30L, params.stepSec());
        assertEquals(31, params.pointCount());
        assertEquals(60.0, params.laneHalfWidthM(), 1e-12);
    }

    @Test
    @DisplayName("与步骤 C/D 串联：滤波器状态可直接喂进预测，输出可投影成 EstimatedPoint")
    void withFilters_shouldProduceEstimatedPoints() {
        AlongTrackKalmanFilter alongFilter = new AlongTrackKalmanFilter(1e-6, 20.0, 75.0, 6.0, 0.5);
        alongFilter.initialize(1000.0, 2.5, 0.0, 0.0);
        LateralOuFilter lateralFilter = new LateralOuFilter(LateralOuFilter.Params.defaults());
        lateralFilter.initialize(5.0, T0_MS);

        MultiStepPredictor.Params params = params(60, 300, 0.15, 0.02);
        MultiStepPredictor.Input in = new MultiStepPredictor.Input(T0_MS,
                alongFilter.s(), alongFilter.vAlongMs(),
                alongFilter.varianceS(), alongFilter.covarianceSV(), alongFilter.varianceV(),
                MultiStepPredictor.LateralForecast.from(lateralFilter, lateralFilter.varianceE(),
                        LateralOuFilter.Params.defaults().sigmaEM(),
                        LateralOuFilter.Params.defaults().tauESec()),
                NORTH, MultiStepPredictor.SpeedLimitLookup.unbounded());

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(in, params);
        assertEquals(6, samples.size());
        EstimatedPoint first = samples.get(0).toEstimatedPoint();
        assertEquals(T0_MS, first.tMs());
        assertTrue(first.sigmaAlongM() > 0.0);
        assertTrue(first.semiMajorM() > first.semiMinorM());
        assertTrue(samples.get(5).sigmaAlongM() > samples.get(0).sigmaAlongM());
    }
}
