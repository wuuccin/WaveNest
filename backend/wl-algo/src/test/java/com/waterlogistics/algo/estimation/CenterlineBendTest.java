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
}
