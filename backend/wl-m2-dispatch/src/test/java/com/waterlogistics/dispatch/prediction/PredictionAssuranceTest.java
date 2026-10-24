package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 区间断言网单测（M2 · 卢肄文 · 10-23 F4 第 1 步「先补网」）。
 *
 * <p>每条违约代码都要能被一条最小用例打中——否则那张网就是装饰。
 */
class PredictionAssuranceTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final long NOW = T0 + 60_000L;

    private final PredictionAssurance assurance =
            new PredictionAssurance(PredictionAssurance.Bounds.ganRiver());

    private static List<EstimatedPoint> series(double lon, double lat) {
        List<EstimatedPoint> points = new ArrayList<>();
        for (int index = 0; index <= 5; index++) {
            points.add(new EstimatedPoint(T0 + index * 60_000L, lon + index * 1e-4, lat,
                    20.0 + index, 10.0 + index * 0.5));
        }
        return points;
    }

    private static PredictionAssurance.Code codeOf(
            java.util.Optional<PredictionAssurance.Violation> violation) {
        return violation.orElseThrow().code();
    }

    @Test
    @DisplayName("合规输出：结构判定与含框判定都通过")
    void validSeries_shouldPass() {
        List<EstimatedPoint> points = series(115.90, 28.65);

        assertTrue(assurance.checkStructure(T0, NOW, 900L, points).isEmpty());
        assertTrue(assurance.check(T0, NOW, 900L, points).isEmpty());
    }

    @Test
    @DisplayName("空点列、负有效期")
    void emptyAndNegativeValidity_shouldBeCaught() {
        assertEquals(PredictionAssurance.Code.EMPTY,
                codeOf(assurance.checkStructure(T0, NOW, 900L, List.of())));
        assertEquals(PredictionAssurance.Code.VALIDITY_NEGATIVE,
                codeOf(assurance.checkStructure(T0, NOW, -1L, series(115.90, 28.65))));
    }

    @Test
    @DisplayName("NaN 与无穷：任何一项出现即拦")
    void nonFinite_shouldBeCaught() {
        List<EstimatedPoint> points = series(115.90, 28.65);
        points.set(2, new EstimatedPoint(T0 + 120_000L, Double.NaN, 28.65, 20.0, 10.0));

        assertEquals(PredictionAssurance.Code.NON_FINITE,
                codeOf(assurance.checkStructure(T0, NOW, 900L, points)));
    }

    @Test
    @DisplayName("σ 非正、σ 下降：两条都要拦")
    void sigma_shouldBePositiveAndMonotone() {
        List<EstimatedPoint> zero = series(115.90, 28.65);
        zero.set(1, new EstimatedPoint(zero.get(1).tMs(), zero.get(1).lon(), zero.get(1).lat(),
                0.0, 10.0));
        assertEquals(PredictionAssurance.Code.SIGMA_NON_POSITIVE,
                codeOf(assurance.checkStructure(T0, NOW, 900L, zero)));

        List<EstimatedPoint> shrinking = series(115.90, 28.65);
        shrinking.set(3, new EstimatedPoint(shrinking.get(3).tMs(), shrinking.get(3).lon(),
                shrinking.get(3).lat(), 1.0, 10.5));
        assertEquals(PredictionAssurance.Code.SIGMA_NOT_MONOTONE,
                codeOf(assurance.checkStructure(T0, NOW, 900L, shrinking)));
    }

    @Test
    @DisplayName("时刻不递增、起点在远未来")
    void time_shouldBeForwardOnly() {
        List<EstimatedPoint> points = series(115.90, 28.65);
        points.set(4, new EstimatedPoint(points.get(3).tMs(), points.get(4).lon(), points.get(4).lat(),
                24.0, 12.0));
        assertEquals(PredictionAssurance.Code.TIME_NOT_MONOTONE,
                codeOf(assurance.checkStructure(T0, NOW, 900L, points)));

        assertEquals(PredictionAssurance.Code.T0_IN_FUTURE,
                codeOf(assurance.checkStructure(NOW + 600_000L, NOW, 900L, series(115.90, 28.65))));
    }

    @Test
    @DisplayName("★ 地理框只拦含框判定：同一份数据结构通过、含框不通过")
    void bounds_shouldOnlyAffectGeographicCheck() {
        List<EstimatedPoint> outside = series(121.47, 30.50);

        assertTrue(assurance.checkStructure(T0, NOW, 900L, outside).isEmpty(),
                "结构判定与地理位置无关");
        assertEquals(PredictionAssurance.Code.OUT_OF_BOUNDS,
                codeOf(assurance.check(T0, NOW, 900L, outside)));
    }

    @Test
    @DisplayName("★ 演示域框必须放行 M1 仿真器坐标（114.30E／30.60N），否则联调每帧被拦")
    void demoAreaBounds_shouldAdmitSimulatorCoordinates() {
        PredictionAssurance demo = new PredictionAssurance(PredictionAssurance.Bounds.demoArea());
        List<EstimatedPoint> simulator = series(114.30, 30.60);

        assertTrue(demo.check(T0, NOW, 900L, simulator).isEmpty());
        assertTrue(demo.bounds().contains(115.90, 28.65), "赣江航段也在演示域内");
        assertTrue(!demo.bounds().contains(121.47, 30.50), "上海坐标必须被拦");
    }
}
