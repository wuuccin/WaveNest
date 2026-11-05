package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.PremiseStatus;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 中心线参考线与前提监测的接线验证。
 *
 * <p>中心线一旦给出，参考线固定取中心线，单条观测即可产出预测；不发中心线时，
 * 仍要等实测窗口凑够两点才能建退化的参考线。前提监测按停止判据给出一条
 * 可预期的 {@code INVALID}，证明结论不是写死的 {@code VALID}。
 */
class PrecisePredictionEngineCenterlineTest {

    private static final PredictionSettings SETTINGS =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    private static final WaterwayGeometry CENTERLINE = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.879, 28.692)));

    @Test
    void singleObservation_withCenterline_shouldPredictImmediately() {
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE);

        assertTrue(engine.predict(observation(115.860, 28.660, 8.0, 27.0, 1000L)).isPresent(),
                "有中心线时单条观测即可预测");
    }

    @Test
    void singleObservation_withoutCenterline_shouldWaitForEnoughPoints() {
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults());

        assertTrue(engine.predict(observation(115.860, 28.660, 8.0, 27.0, 1000L)).isEmpty(),
                "无中心线时单条观测不足以建参考线");
    }

    @Test
    void stoppedShip_shouldFlagPremiseInvalid() {
        PrecisePredictionEngine engine = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE);

        PrecisePredictionEngine.Outcome last = null;
        for (int i = 0; i < 8; i++) {
            last = engine.predict(observation(115.860, 28.660, 0.0, 27.0, 1000L + i * 40_000L))
                    .orElseThrow();
        }

        assertEquals(PremiseStatus.INVALID, last.premise(), "长时间停船应判前提失效");
    }

    @Test
    void speedLimit_shouldSlowDownOverspeedShip() {
        // 限速 2.0 m/s 与不限速两个引擎，喂同一条 8 m/s 沿中心线航行的超速船。
        PrecisePredictionEngine limited = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE,
                SpeedLimitLookup.constant(2.0));
        PrecisePredictionEngine unlimited = new PrecisePredictionEngine(
                SETTINGS, PrecisePredictionEngine.Params.defaults(), CENTERLINE,
                SpeedLimitLookup.unbounded());

        PrecisePredictionEngine.Outcome a = limited
                .predict(observation(115.860, 28.660, 8.0, 27.0, 1000L)).orElseThrow();
        PrecisePredictionEngine.Outcome b = unlimited
                .predict(observation(115.860, 28.660, 8.0, 27.0, 1000L)).orElseThrow();

        // 中心线 lon 单调递增（115.852 → 115.879），末点 lon 越小＝沿程推进越少。
        double limitedEndLon = lastLon(a);
        double unlimitedEndLon = lastLon(b);
        assertTrue(limitedEndLon < unlimitedEndLon,
                "限速后沿程推进应少于不限速：limited=" + limitedEndLon
                        + " unlimited=" + unlimitedEndLon);
    }

    private static double lastLon(PrecisePredictionEngine.Outcome outcome) {
        List<EstimatedPoint> points = outcome.points();
        return points.get(points.size() - 1).lon();
    }

    private static AisMessage observation(double lon, double lat, double sog, double cog, long ts) {
        return new AisMessage("413000001", "测试船", lon, lat, sog, cog, ts);
    }
}
