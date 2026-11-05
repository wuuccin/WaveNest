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

    // =================================================================
    // 11-02 MEDIUM.44：把「ETA 不早于当前时刻」落成可判定的两条
    // =================================================================

    @Test
    @DisplayName("★ 整版预测落在过去 ⇒ STALE（原实现放行这种快照）")
    void staleSnapshot_shouldBeCaught() {
        // 点列覆盖 T0..T0+300 s，而「现在」是 T0+3600 s：这一版对当前调度毫无价值。
        // 改前只判「t0 不在未来」＋「点列内部单调」，两条都满足，于是被放行。
        List<EstimatedPoint> ended = series(115.90, 28.65);

        assertEquals(PredictionAssurance.Code.STALE,
                codeOf(assurance.checkStructure(T0, T0 + 3_600_000L, 900L, ended)));
    }

    @Test
    @DisplayName("STALE 的边界：末点恰好等于现在不判违约，超出 300 s 时钟容差才判")
    void staleBoundary_shouldRespectClockSkewTolerance() {
        List<EstimatedPoint> points = series(115.90, 28.65);
        long lastTs = points.get(points.size() - 1).tMs();

        assertTrue(assurance.checkStructure(T0, lastTs, 900L, points).isEmpty(),
                "末点＝现在，不该判过期");
        assertTrue(assurance.checkStructure(T0, lastTs + 300_000L, 900L, points).isEmpty(),
                "正好用满 300 s 容差仍算新鲜（AIS 时基有漂移）");
        assertEquals(PredictionAssurance.Code.STALE,
                codeOf(assurance.checkStructure(T0, lastTs + 300_001L, 900L, points)));
    }

    @Test
    @DisplayName("★ 首点没锚在起点上 ⇒ FIRST_POINT_BEFORE_T0（按 τ 取点的口径会整体错位）")
    void firstPointBeforeT0_shouldBeCaught() {
        List<EstimatedPoint> shifted = new ArrayList<>();
        // τ=0 的点被放到 t0 之前 10 分钟：后面所有「第 k 个点对应 τ=k·step」的解读都错了
        for (int index = 0; index <= 5; index++) {
            shifted.add(new EstimatedPoint(T0 - 600_000L + index * 60_000L, 115.90, 28.65,
                    20.0, 10.0));
        }

        assertEquals(PredictionAssurance.Code.FIRST_POINT_BEFORE_T0,
                codeOf(assurance.checkStructure(T0, NOW, 900L, shifted)));
    }

    @Test
    @DisplayName("违约文案里的经纬度按 Locale.ROOT 格式化（德语环境不会变成 114,30000）")
    void violationDetail_shouldUseRootLocale() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            String detail = assurance.check(T0, NOW, 900L, series(121.47, 30.50))
                    .orElseThrow().detail();

            assertTrue(detail.contains("121.47000"), "小数点必须是点号，否则日志与 grep 全废：" + detail);
            assertTrue(!detail.contains("121,47000"), detail);
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
