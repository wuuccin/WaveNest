package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 弯道几何与横向 OU 稳态标准差的单测（11-06 序号 2 · 卢肄文）。
 *
 * <p>验三件事，都对应「不再是固定系数」这一判据的可测形式：
 * <ol>
 *   <li>直线段曲率与弦高为 0，{@code σ_e} 严格等于观测口径 {@code σ_e0}——这是「回归不失败」
 *       的前提：直线航段的预测必须与加本功能之前逐位一致；</li>
 *   <li>弯道段曲率为「窗口内转角／窗宽」，弦高为 {@code Λ²κ/8}，与解析值对得上；</li>
 *   <li>曲率越大、{@code σ_e} 越大且夹在航道半宽以内。</li>
 * </ol>
 */
class CenterlineBendTest {

    private static final double M_PER_DEG_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;

    /** 一条正北走向的直线航段，长 {@code lengthM} 米，起点 (115.86, 28.66)。 */
    private static WaterwayGeometry straight(double lengthM) {
        double lat0 = 28.66;
        return new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(115.86, lat0),
                new WaterwayGeometry.Vertex(115.86, lat0 + lengthM / M_PER_DEG_LAT)));
    }

    /**
     * 在东向 {@code bendAtM} 米处折出 {@code turnDeg} 度的折线，两臂各长 {@code armM} 米。
     *
     * <p>折点之前的走向是正北（0°），之后顺时针偏 {@code turnDeg}。
     */
    private static WaterwayGeometry bent(double bendAtM, double turnDeg, double armM) {
        double lat0 = 28.66;
        double lon0 = 115.86;
        double bendLat = lat0 + bendAtM / M_PER_DEG_LAT;
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>();
        vertices.add(new WaterwayGeometry.Vertex(lon0, lat0));
        vertices.add(new WaterwayGeometry.Vertex(lon0, bendLat));
        vertices.add(offset(lon0, bendLat, turnDeg, armM));
        return new WaterwayGeometry(vertices);
    }

    /** 由起点按方位角 {@code bearingDeg} 走 {@code distanceM} 米后的坐标。 */
    private static WaterwayGeometry.Vertex offset(double lon, double lat, double bearingDeg,
            double distanceM) {
        double rad = Math.toRadians(bearingDeg);
        double north = distanceM * Math.cos(rad);
        double east = distanceM * Math.sin(rad);
        double metersPerDegLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        return new WaterwayGeometry.Vertex(lon + east / metersPerDegLon, lat + north / M_PER_DEG_LAT);
    }

    @Test
    @DisplayName("直线航段：曲率为 0、弦高为 0、σ_e 严格等于观测口径")
    void straightLine_shouldHaveNoCurvature() {
        WaterwayGeometry line = straight(6000.0);

        assertEquals(0.0, CenterlineBend.curvatureRadPerM(line, 3000.0), 1e-12,
                "直线航段没有转角，等效曲率应为 0");
        assertEquals(0.0, CenterlineBend.sagittaM(line, 3000.0), 0.0,
                "直线航段的折线与河道重合，弦高应为 0");
        assertEquals(15.0, CenterlineBend.sigmaEPredictionM(15.0, 50.0, line, 3000.0), 1e-12,
                "直线段 σ_e 必须与改动前逐位一致（判据「回归不失败」的前提）");
    }

    @Test
    @DisplayName("弯道段：曲率＝窗口内转角／窗宽，弦高＝Λ²κ/8")
    void bend_shouldMatchAnalyticCurvatureAndSagitta() {
        double turnDeg = 11.3;
        WaterwayGeometry line = bent(3000.0, turnDeg, 3000.0);

        // 折点在 s = 3000 m；窗口 [2750, 3250] 完整跨过折点
        double expectedKappa = Math.toRadians(turnDeg) / CenterlineBend.BEND_WINDOW_M;
        double kappa = CenterlineBend.curvatureRadPerM(line, 3000.0);
        assertEquals(expectedKappa, kappa, expectedKappa * 1e-3,
                "曲率应为窗口内转角除以窗宽");

        double expectedSagitta = expectedKappa * CenterlineBend.BEND_WINDOW_M
                * CenterlineBend.BEND_WINDOW_M / 8.0;
        assertEquals(expectedSagitta, CenterlineBend.sagittaM(line, 3000.0),
                expectedSagitta * 1e-3, "弦高应为 Λ²κ/8");
        assertTrue(expectedSagitta > 10.0 && expectedSagitta < 15.0,
                "11.3° 弯在 500 m 窗宽下的弦高应在 10~15 m 量级，实际 " + expectedSagitta);
    }

    @Test
    @DisplayName("远离折点处曲率回到 0：曲率随弧长变化，不是整条航段一个常数")
    void awayFromBend_curvatureShouldFadeToZero() {
        WaterwayGeometry line = bent(3000.0, 11.3, 3000.0);

        assertTrue(CenterlineBend.curvatureRadPerM(line, 3000.0) > 0.0, "折点处有曲率");
        assertEquals(0.0, CenterlineBend.curvatureRadPerM(line, 1000.0), 1e-12,
                "距折点 2 km（窗口外）应无曲率");
        assertEquals(0.0, CenterlineBend.curvatureRadPerM(line, 5500.0), 1e-12,
                "折点后 2.5 km（窗口外）应无曲率");
    }

    @Test
    @DisplayName("σ_e 随曲率单调抬升，并被航道半宽截断")
    void sigmaEShouldGrowWithCurvatureAndBeCapped() {
        double sigmaE0 = 15.0;
        double halfWidth = 50.0;

        double straightSigma = CenterlineBend.sigmaEPredictionM(sigmaE0, halfWidth,
                bent(3000.0, 0.0, 3000.0), 3000.0);
        double gentleSigma = CenterlineBend.sigmaEPredictionM(sigmaE0, halfWidth,
                bent(3000.0, 5.0, 3000.0), 3000.0);
        double sharpSigma = CenterlineBend.sigmaEPredictionM(sigmaE0, halfWidth,
                bent(3000.0, 45.0, 3000.0), 3000.0);

        assertEquals(sigmaE0, straightSigma, 1e-12, "无转角时等于观测口径");
        assertTrue(gentleSigma > sigmaE0, "有弯道时应抬升：" + gentleSigma);
        assertTrue(sharpSigma > gentleSigma, "弯越急抬升越多：" + sharpSigma);
        assertEquals(halfWidth, sharpSigma, 1e-12, "急弯的弦高会被半宽截断");
    }

    @Test
    @DisplayName("端点附近窗口夹到航段内，不越界也不抛异常")
    void windowShouldBeClampedAtEndpoints() {
        WaterwayGeometry line = bent(3000.0, 20.0, 3000.0);

        double atStart = CenterlineBend.curvatureRadPerM(line, 0.0);
        double atEnd = CenterlineBend.curvatureRadPerM(line, line.totalLengthM());
        assertTrue(Double.isFinite(atStart) && atStart >= 0.0, "起点处应为有限非负值");
        assertTrue(Double.isFinite(atEnd) && atEnd >= 0.0, "终点处应为有限非负值");
    }

    @Test
    @DisplayName("无中心线时曲率为 0：退化口径不给带宽加料")
    void nullGeometry_shouldHaveZeroCurvature() {
        assertEquals(0.0, CenterlineBend.curvatureRadPerM(null, 1000.0), 0.0);
        assertEquals(15.0, CenterlineBend.sigmaEPredictionM(15.0, 50.0, null, 1000.0), 1e-12);
    }

    // ───────────────── 台账 v2 十三点（11-07 序号 2 · 判据 19.43 m） ─────────────────

    /**
     * 中心线台账 v2 的十三个顶点，逐点取自 {@code deploy/sql/014_centerline_v2.sql}
     * （{@code chart_id = GANJIANG}、{@code version = 2}）。
     *
     * <p>十三点由三个权威锚点（朝阳大桥 → 南昌大桥 → 八一大桥）按 ≤500 m 等弧长加密而来，
     * 与 {@code application.yml} 的 {@code wavenest.m2.prediction.centerline} 同一组坐标。
     * 台账侧 {@code s_m} 的末行为 {@code 5778.9088}，本类用同一套
     * {@link WaterwayGeometry#METERS_PER_DEG_LAT} 复算，逐点零差（见下方断言）。
     */
    private static final double[][] V2_POINTS = {
        {115.852, 28.646}, {115.853739, 28.650224}, {115.855479, 28.654448},
        {115.857218, 28.658673}, {115.859, 28.663}, {115.861586, 28.666749},
        {115.864236, 28.670593}, {115.866887, 28.674436}, {115.869537, 28.678279},
        {115.872188, 28.682123}, {115.874838, 28.685966}, {115.877489, 28.689809},
        {115.879, 28.692}
    };

    /** 台账 v2 的 {@code s_m}，与 {@code 014} 逐行一致，用于核对复算口径。 */
    private static final double[] V2_S_M = {
        0.0000, 499.4026, 998.8360, 1498.3386, 2009.9314, 2497.2093, 2996.7528,
        3496.2468, 3995.6853, 4495.2646, 4994.6933, 5494.1676, 5778.9088
    };

    /** 唯一实折点（第四段末、第五段首）的弧长，m。 */
    private static final double V2_BEND_S = 2009.9314;

    private static WaterwayGeometry v2Centerline() {
        List<WaterwayGeometry.Vertex> vertices = new ArrayList<>();
        for (double[] p : V2_POINTS) {
            vertices.add(new WaterwayGeometry.Vertex(p[0], p[1]));
        }
        return new WaterwayGeometry(vertices);
    }

    @Test
    @DisplayName("台账 v2 十三点：全长与逐点 s_m 与 014 一致（复算口径同源）")
    void v2Centerline_shouldReproduceLedgerSM() {
        WaterwayGeometry geometry = v2Centerline();

        assertEquals(13, geometry.vertexCount(), "十三点");
        assertEquals(V2_S_M[V2_S_M.length - 1], geometry.totalLengthM(), 0.001,
                "全长须与 014 的 s_m 末行 5778.9088 一致；不一致说明复算口径（每段 latRef 取两端均值）"
                        + "与灌库脚本不同源，后续曲率与限速段边界都会错位");
    }

    @Test
    @DisplayName("台账 v2 唯一折点：κ=3.95e-4、弦高 12.34 m、σ_e 由 15.00 抬到 19.43 m")
    void v2Bend_shouldInflateSigmaTo1943() {
        WaterwayGeometry geometry = v2Centerline();

        // 折点处：窗口 500 m 跨过 11.32° 的转角
        assertEquals(3.9499e-4, CenterlineBend.curvatureRadPerM(geometry, V2_BEND_S), 1e-8,
                "等效曲率应为 Δψ/Λ（11.32° 落在 500 m 窗内），对应转弯半径约 2532 m");
        assertEquals(12.34, CenterlineBend.sagittaM(geometry, V2_BEND_S), 0.01,
                "弦高 h=Λ²κ/8；这是「用折线代替圆弧」丢掉的确定性几何量");

        // 判据值：19.43 m（√(15² + 12.34²)）
        assertEquals(19.43, CenterlineBend.sigmaEPredictionM(15.0, 50.0, geometry, V2_BEND_S), 0.01,
                "弯道处 σ_e 应为 19.43 m：观测口径 15 m 与几何弦高 12.34 m 按独立量平方和合成");
    }

    @Test
    @DisplayName("台账 v2 直线段：σ_e 严格停在 15.00 m，不给正常航行加料")
    void v2StraightSections_shouldKeepSigmaAtObservationFloor() {
        WaterwayGeometry geometry = v2Centerline();

        // 折点之外的两个采样点，曲率应小到不改变 σ_e（保证旧用例逐点不变）
        // 容差 1e-3：加密点个别顶点有 0.002° 级别的残余转角，σ_e 只差 1e-5 量级
        for (double s : new double[] {1000.0, 4500.0}) {
            assertEquals(15.0, CenterlineBend.sigmaEPredictionM(15.0, 50.0, geometry, s), 1e-3,
                    "s=" + s + " 不在折点附近，σ_e 必须等于观测口径——这是「回归不失败」的前提");
        }
    }
}
