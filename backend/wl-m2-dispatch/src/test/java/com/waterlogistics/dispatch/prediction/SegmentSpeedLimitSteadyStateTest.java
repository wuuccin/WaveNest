package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.MultiStepPredictor;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 末段限速对预测速度的约束（11-05 序号 1 · 吴可欣）。
 *
 * <p>硬判据 7 的后半段：末段（4000–5780 m）稳态预测点速度 ≤ 2.6 m/s（限速 2.5 ＋ 容差 0.1）。
 *
 * <p>现场参数取 11-05 彭紫情汇合复跑：船 s0 = 2690 m、AIS sog 4.12 m/s、cog 与中心线走向相差约
 * 4°，故纵向初速 v0 = 4.12·cos 4° ≈ 4.11 m/s。时域 720 s：行程约 2.9 km，末点弧长约 5650 m，
 * 不触中心线末端，避免夹紧干扰速度核算。
 */
class SegmentSpeedLimitSteadyStateTest {

    /** 赣江演示航段两端（与 {@code application.yml} 的 centerline 同端点），弧长约 5.78 km。 */
    private static final WaterwayGeometry GANJIANG = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.879, 28.692)));

    /** 与 {@code application.yml} 的 {@code segment-speed-limits} 同一串。 */
    private static final String SPEED_LIMIT_SPEC = "0,2000,4.5;2000,4000,4.5;4000,5780,2.5";

    private static final double LAST_SEGMENT_FROM_S = 4000.0;
    private static final double FIRST_SEGMENT_LIMIT_MS = 4.5;
    private static final double LAST_SEGMENT_LIMIT_MS = 2.5;
    private static final double STEADY_SPEED_TOLERANCE_MS = 0.1;
    private static final double STEADY_START_TAU_SEC = 180.0;

    @Test
    @DisplayName("末段稳态速度 ≤ 限速 2.5 ＋ 容差 0.1；进末段前不受前段限速约束")
    void lastSegment_shouldConvergeUnderSpeedLimit() {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse(SPEED_LIMIT_SPEC);
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, MultiStepPredictor.DEFAULT_TAU_V_SEC,
                0.0, 0.0, 50.0, 180.0, 40.0, 60L, 720L);
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(
                1_700_000_000_000L, 2690.0, 4.11, 400.0, 0.0, 0.0,
                MultiStepPredictor.LateralForecast.ou(0.0, 0.0, 0.0, 120.0, 15.0),
                GANJIANG, lookup);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);

        boolean sawLastSegment = false;
        boolean sawSteady = false;
        double tauEnterLastSegment = Double.NaN;
        for (int i = 1; i < samples.size(); i++) {
            double sFrom = samples.get(i - 1).s();
            double dtSec = samples.get(i).tauSec() - samples.get(i - 1).tauSec();
            assertTrue(dtSec > 0.0, "预测点的 τ 必须严格递增");
            double speed = (samples.get(i).s() - sFrom) / dtSec;
            if (sFrom < LAST_SEGMENT_FROM_S) {
                assertTrue(speed <= FIRST_SEGMENT_LIMIT_MS + STEADY_SPEED_TOLERANCE_MS,
                        "末段之前速度 " + speed + " m/s 超出前段限速 4.5 ＋ 容差");
                continue;
            }
            if (!sawLastSegment) {
                sawLastSegment = true;
                tauEnterLastSegment = samples.get(i - 1).tauSec();
            }
            // 稳态判据：进入末段后再过 3·tauV = 180 s（限速约束从进入末段那一刻才起作用）。
            if (samples.get(i - 1).tauSec() < tauEnterLastSegment + STEADY_START_TAU_SEC) {
                continue;
            }
            sawSteady = true;
            assertTrue(speed <= LAST_SEGMENT_LIMIT_MS + STEADY_SPEED_TOLERANCE_MS,
                    "末段 τ=" + samples.get(i - 1).tauSec() + " s 段速度 " + speed
                            + " m/s 超出限速 2.5 ＋ 容差 0.1");
        }
        assertTrue(sawLastSegment, "预测未进入末段（4000 m 以后），判据未被执行");
        assertTrue(sawSteady, "末段没有稳态数据（τ ≥ 180 s），判据未被执行");
    }
}
