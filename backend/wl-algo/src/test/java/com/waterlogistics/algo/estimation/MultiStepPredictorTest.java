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

    // ───────────── 11-04 内部顶点边界冻结（吴可欣 M4 现场验证发现） ─────────────

    /** 赣江 13 顶点中心线，与 {@code application.yml} 的 {@code centerline} 同值（M4 现场验证那条）。 */
    private static final WaterwayGeometry GANJIANG = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.853739, 28.650224),
            new WaterwayGeometry.Vertex(115.855479, 28.654448),
            new WaterwayGeometry.Vertex(115.857218, 28.658673),
            new WaterwayGeometry.Vertex(115.859, 28.663),
            new WaterwayGeometry.Vertex(115.861586, 28.666749),
            new WaterwayGeometry.Vertex(115.864236, 28.670593),
            new WaterwayGeometry.Vertex(115.866887, 28.674436),
            new WaterwayGeometry.Vertex(115.869537, 28.678279),
            new WaterwayGeometry.Vertex(115.872188, 28.682123),
            new WaterwayGeometry.Vertex(115.874838, 28.685966),
            new WaterwayGeometry.Vertex(115.877489, 28.689809),
            new WaterwayGeometry.Vertex(115.879, 28.692)));

    /** M4 现场验证的分段限速：{@code s ∈ [2000, 4000)} 限 3.0 m/s，其余不限速。 */
    private static final MultiStepPredictor.SpeedLimitLookup M4_LIMIT =
            s -> (s >= 2000.0 && s < 4000.0) ? 3.0 : Double.MAX_VALUE;

    @Test
    @DisplayName("内部顶点边界：解析积分差几纳米时不得丢弃剩余时间（11-04 冻结缺陷）")
    void internalVertexBoundary_shouldNotFreezeAlongTrack() {
        // 复现参数与《多步预测边界冻结缺陷说明》§一 一致：s0=2969.9、v0=4.116、
        // 限速段 [2000,4000) 取 3.0、tauV=60 s、步长 60 s、时域 1800 s。
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, MultiStepPredictor.DEFAULT_TAU_V_SEC,
                0.10, 0.03, 50.0, 180.0, 40.0, 60L, 1800L);
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(T0_MS, 2969.9, 4.116,
                400.0, 0.0, 0.0, stillLateral(), GANJIANG, M4_LIMIT);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);
        double total = GANJIANG.totalLengthM();
        double last = samples.get(samples.size() - 1).s();

        // 判据 1：不再冻结。旧实现停在顶点边界前 2.4 nm（s≈3995.685），此后 24 点不动；
        // 修好后 3.0 m/s 跑满 1800 s 足以走完全线，末点应夹在端点。
        assertTrue(last > 3996.0, "末点 s=" + last + "，仍冻结在顶点边界 3995.685 附近");
        assertEquals(total, last, 1e-9, "走完全线后应夹在端点");

        // 判据 2：s 单调不减，且未到端点前每个点都要真的走一步（原地踏步就是冻结）。
        // 一次 60 s 的理论推进量在 180 m（限速 3.0 稳态）到 222 m（首步 4.116 衰减中）之间，
        // 取下界 179 m 判「有没有走」；临近端点那一步取剩余距离。
        for (int i = 1; i < samples.size(); i++) {
            double prev = samples.get(i - 1).s();
            double cur = samples.get(i).s();
            assertTrue(cur >= prev - 1e-9, "τ=" + samples.get(i).tauSec() + " 的 s 回退");
            if (prev < total - 1e-6) {
                double expected = Math.min(179.0, total - prev);
                assertTrue(cur - prev >= expected - 1e-6,
                        "τ=" + samples.get(i).tauSec() + " 只推进 " + (cur - prev)
                                + " m（应为 " + expected + " m 量级，原地踏步＝冻结）");
            }
        }
    }

    @Test
    @DisplayName("跨内部顶点后：按新航段限速重新起算（80 §3.2.7 步骤 3）")
    void crossingInternalVertex_shouldRestartWithNewSegmentLimit() {
        WaterwayGeometry three = new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(LON, BASE_LAT),
                new WaterwayGeometry.Vertex(LON, BASE_LAT + 0.02),
                new WaterwayGeometry.Vertex(LON, BASE_LAT + 0.06)));
        double boundary = three.segmentBoundariesM()[1];

        // 第一段不限速、第二段限 2.0 m/s；船以 4.0 m/s 从边界前 120 m 出发，30 s 后跨过边界。
        MultiStepPredictor.SpeedLimitLookup limit =
                s -> s >= boundary ? 2.0 : Double.MAX_VALUE;
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, 60.0, 0.0, 0.0, 50.0, 180.0, 40.0, 60L, 60L);
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(T0_MS, boundary - 120.0, 4.0,
                400.0, 0.0, 0.0, stillLateral(), three, limit);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);

        // 跨过边界后的 30 s 就必须按 2.0 m/s 起算：2·30 + 2·60·(1−e^{−0.5}) = 107.21 m。
        // 若晚一个子步才换限速（整步按旧段 4.0），这里会是 120 m。
        double advanced = samples.get(1).s() - boundary;
        assertEquals(107.21, advanced, 0.2,
                "跨段后未按新航段限速重新起算：推进 " + advanced + " m");
    }

    @Test
    @DisplayName("限速切换点不与顶点重合：子步在切换点处切断，跨点后按新限速重新起算")
    void limitSwitchPointOffVertex_shouldSplitSubStep() {
        // 几何只有两端点——一条航段、零内部顶点；限速切换点设在 s = 1000 m，必不与任何顶点重合。
        // 旧实现只按几何顶点切分，子步跨过切换点后整步沿用「切换前不限速」，正是本条要修的缺陷。
        WaterwayGeometry twoVertices = new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(LON, BASE_LAT),
                new WaterwayGeometry.Vertex(LON, BASE_LAT + 0.06)));
        double switchPoint = 1000.0;
        MultiStepPredictor.SpeedLimitLookup limit = new MultiStepPredictor.SpeedLimitLookup() {
            @Override
            public double speedLimitMs(double s) {
                return s < switchPoint ? Double.MAX_VALUE : 2.0;
            }

            @Override
            public double[] switchPointsM() {
                return new double[] {switchPoint};
            }
        };
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, 60.0, 0.0, 0.0, 50.0, 180.0, 40.0, 60L, 60L);
        // 船以 4.0 m/s 从切换点前 120 m 出发：前 30 s 走满 120 m 到切换点，后 30 s 按 2.0 衰减。
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(T0_MS, switchPoint - 120.0, 4.0,
                400.0, 0.0, 0.0, stillLateral(), twoVertices, limit);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);

        // 切分后：2·30 + 2·60·(1−e^{−0.5}) = 107.216 m。
        // 若未切分（整步按跨越前的 4.0 匀速），这里是 120 m——差 12.8 m，远超 0.2 容差。
        double advanced = samples.get(1).s() - switchPoint;
        assertEquals(107.21, advanced, 0.2,
                "子步未在限速切换点断开、跨点后仍沿用旧限速：推进 " + advanced + " m");
        // 切分只改子步划分，不得改变预测时间点。
        assertEquals(60.0, samples.get(1).tauSec(), 1e-9);
    }

    // ───────────── 非空限速段的速度稳态（11-05 判据 3 口径裁定） ─────────────

    /** 判据 3 的稳态门槛：{@code 3·tauV}，s（{@code 80} 附录 B 的 {@code tauV = 60 s}）。 */
    private static final long STEADY_START_TAU_SEC = 3L * (long) MultiStepPredictor.DEFAULT_TAU_V_SEC;

    /** 稳态段允许的限速容差，m/s：0.1 约等于两个 AIS {@code sog} 量化步长（0.1 kn ≈ 0.051 m/s）。 */
    private static final double STEADY_SPEED_TOLERANCE_MS = 0.1;

    @Test
    @DisplayName("非空限速段：一阶衰减在 τ≥3·tauV 后收敛进限速容差带（判据 3 口径）")
    void speedLimitFirstOrderDecay_shouldConvergeInSteadyState() {
        // 现场参数（11-05 彭紫情汇合复跑）：船 s0 = 2690 m；AIS sog 4.12 m/s，cog 与中心线走向
        // 相差约 4°，故纵向初速 v0 = 4.12·cos 4° ≈ 4.11 m/s（AlongTrackKalmanFilter 的初始化口径）。
        // 限速配置全程 2.5 m/s（`0,3000,2.5;3000,5780,2.5` 两段同值，等价于常数限速）。
        // 步长 60 s、时域 720 s：最长行程约 1.9 km，不触及中心线端点，避免夹紧干扰速度核算。
        double v0 = 4.11;
        double limitMs = 2.5;
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, MultiStepPredictor.DEFAULT_TAU_V_SEC,
                0.0, 0.0, 50.0, 180.0, 40.0, 60L, 720L);
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(T0_MS, 2690.0, v0,
                400.0, 0.0, 0.0, stillLateral(), GANJIANG,
                MultiStepPredictor.SpeedLimitLookup.constant(limitMs));

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);

        // 相邻点弧长差 ÷ 步长 = 该段平均速度。用弧长而非经纬度地面距离：弧长无投影误差，
        // 转弯段也不会因弦长小于弧长而低估速度。
        double[] segmentSpeed = new double[samples.size() - 1];
        for (int i = 1; i < samples.size(); i++) {
            segmentSpeed[i - 1] = (samples.get(i).s() - samples.get(i - 1).s()) / params.stepSec();
        }

        // 判据 1：稳态段（起点 τ ≥ 3·tauV = 180 s）每段速度不超过限速 + 容差。
        // 2.5 档理论值：180–240 s 段 2.551、240–300 s 段 2.519、300–360 s 段 2.507。
        boolean sawSteady = false;
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i - 1).tauSec() < STEADY_START_TAU_SEC) {
                continue;
            }
            sawSteady = true;
            assertTrue(segmentSpeed[i - 1] <= limitMs + STEADY_SPEED_TOLERANCE_MS,
                    "τ=" + samples.get(i - 1).tauSec() + "–" + samples.get(i).tauSec()
                            + " s 段速度 " + segmentSpeed[i - 1] + " m/s 超出限速 + "
                            + STEADY_SPEED_TOLERANCE_MS);
        }
        assertTrue(sawSteady, "时域太短，没有落到稳态段，判据未被执行");

        // 判据 2：过渡段速度单调不增（一阶衰减没有回弹）。
        for (int i = 1; i < segmentSpeed.length; i++) {
            assertTrue(segmentSpeed[i] <= segmentSpeed[i - 1] + 1e-9,
                    "第 " + (i + 1) + " 段速度 " + segmentSpeed[i] + " 高于前一段 "
                            + segmentSpeed[i - 1] + "，衰减不是单调的");
        }

        // 判据 3：首段速度必须显著高于限速——证明限速确实接进了预测链路，
        // 而不是像配置缺失那样退化成 unbounded。
        // 理论值 2.5 + 1.61·(1−e⁻¹) = 3.518 m/s，与现场实测「最高约 3.50 m/s」同为过渡段口径。
        assertEquals(limitMs + (v0 - limitMs) * (1.0 - Math.exp(-1.0)), segmentSpeed[0], 0.01,
                "首段速度与一阶衰减解析积分不符：限速可能未接入预测链路");
        assertTrue(segmentSpeed[0] > limitMs + 0.5, "首段速度未显著高于限速，衰减可能未生效");
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
