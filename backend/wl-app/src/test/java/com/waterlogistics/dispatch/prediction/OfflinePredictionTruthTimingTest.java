package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 11-12 序号 2 罗才辉：无噪真值的三个复算值与真值序列口径。
 *
 * <p>本类不依赖 Spring 上下文与数据库，单独钉住契约 §2 的三段限速积分：
 * {@code 4000 ÷ 4.5 = 888.889 s}、{@code 2003.351 ÷ 2.5 = 801.340 s}、
 * 合计 {@code 1690.229 s}。限速表或积分口径被改动时，本类先红。
 */
class OfflinePredictionTruthTimingTest {

    @Test
    @DisplayName("三段限速积分的三个复算值与契约 §2 一致")
    void contractTimings_matchThreeSegmentIntegration() {
        assertEquals(888.889, OfflinePredictionFixture.T_TO_SECOND_SWITCH_SEC, 0.001,
                "s=0 至 4000 的用时应为 888.889 s");
        assertEquals(801.340, OfflinePredictionFixture.T_TO_END_SEC, 0.001,
                "4000 至 6003.351 的用时应为 801.340 s");
        assertEquals(1690.229, OfflinePredictionFixture.T_TOTAL_SEC, 0.001,
                "全程的用时应为 1690.229 s");
        assertEquals(OfflinePredictionFixture.T_TO_SECOND_SWITCH_SEC
                        + OfflinePredictionFixture.T_TO_END_SEC,
                OfflinePredictionFixture.T_TOTAL_SEC, 1e-9,
                "全程的用时应等于两段的用时之和");
    }

    @Test
    @DisplayName("normal_straight 的真值序列为 1801 点、1 Hz、末点为全长")
    void truthSeries_normalStraightEndsAtTotalLength() {
        OfflinePredictionFixture.Spec spec = new OfflinePredictionFixture.Spec(
                1, "T1", 11090001L,
                OfflinePredictionFixture.DEFAULT_T0_MS, 0.0, 1800);
        List<OfflinePredictionFixture.TruthPoint> series =
                OfflinePredictionFixture.truthSeries(spec, OfflinePredictionFixture.v3Centerline());

        assertEquals(1801, series.size(), "1 Hz 的真值序列应为 1801 点");
        assertEquals(0.0, series.get(0).sM(), 1e-9, "首点的弧长应为 0");
        assertEquals(OfflinePredictionFixture.CENTERLINE_TOTAL_M,
                series.get(series.size() - 1).sM(), 1e-4, "末点的弧长应等于中心线的全长");
        assertEquals(spec.t0Ms() + 1800_000L, series.get(series.size() - 1).tsMs(),
                "末点的时刻应为起点加 1800 s");

        double previousS = -1.0;
        for (OfflinePredictionFixture.TruthPoint point : series) {
            assertTrue(point.sM() >= previousS, "弧长应单调不减");
            assertTrue(Double.isFinite(point.lon()) && Double.isFinite(point.lat()),
                    "经纬度应为有限数");
            previousS = point.sM();
        }
    }
}
