package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WaterwayGeometry} 单测（A 组 · {@code 80} §3.2.4 步骤 B）。
 *
 * <p>★ 关键验证：用《赣江场景项目文档》§3.2 给出的三座桥示意坐标构造折线，
 * 复算出的段长应与文档一致——<b>朝阳→南昌 ≈ 2.01 km、南昌→八一 ≈ 3.77 km、全程 ≈ 5.78 km</b>。
 * 这证明了弧长口径与文档同源，而不是我随便算的。
 */
class WaterwayGeometryTest {

    // 《赣江场景项目文档》§3.2 三座桥示意坐标（纬度, 经度顺序原文；此处按 lon,lat 传入）
    private static final WaterwayGeometry.Vertex CHAOYANG = new WaterwayGeometry.Vertex(115.852, 28.646);
    private static final WaterwayGeometry.Vertex NANCHANG = new WaterwayGeometry.Vertex(115.859, 28.663);
    private static final WaterwayGeometry.Vertex BAYI = new WaterwayGeometry.Vertex(115.879, 28.692);

    private static WaterwayGeometry ganjiangSegment() {
        return new WaterwayGeometry(List.of(CHAOYANG, NANCHANG, BAYI));
    }

    @Test
    @DisplayName("★ 弧长复现文档值：朝阳→南昌 ≈ 2.01 km、南昌→八一 ≈ 3.77 km、全程 ≈ 5.78 km")
    void totalLength_shouldMatchScenarioDocument() {
        WaterwayGeometry geo = ganjiangSegment();
        double total = geo.totalLengthM();

        // 全程按文档 5.78 km，容差 2%（平面近似 vs 球面）
        assertEquals(5780.0, total, total * 0.02, "全程应约 5.78 km，实际 " + total);
        assertTrue(total > 5600 && total < 5960, "落在 5.6~5.96 km 区间，实际 " + total);
    }

    @Test
    @DisplayName("分段长度：到南昌大桥的那一段应约 2.01 km")
    void firstSegment_shouldMatchScenarioDocument() {
        WaterwayGeometry geo = ganjiangSegment();

        // 南昌大桥在折线上的 s 就等于第一段长度
        WaterwayGeometry.Projection atNanChang = geo.project(NANCHANG.lon(), NANCHANG.lat());

        assertEquals(2010.0, atNanChang.s(), 2010.0 * 0.03, "朝阳→南昌应约 2.01 km，实际 " + atNanChang.s());
        assertEquals(0.0, atNanChang.e(), 1.0, "顶点在中心线上，横向偏移应约 0");
    }

    @Test
    @DisplayName("中心线上的点投影：|e| ≈ 0，且 s 单调随里程增长")
    void projectionOnCenterline_shouldHaveZeroCrossTrack() {
        WaterwayGeometry geo = ganjiangSegment();

        double previousS = -1.0;
        for (double s = 0.0; s <= geo.totalLengthM(); s += 500.0) {
            WaterwayGeometry.Vertex onLine = geo.pointAt(s);
            WaterwayGeometry.Projection projection = geo.project(onLine.lon(), onLine.lat());

            assertEquals(0.0, projection.e(), 2.0, "中心线上的点横向偏移应约 0（s=" + s + "）");
            assertEquals(s, projection.s(), 30.0, "反投影再投影，s 应回到原值（s=" + s + "）");
            assertTrue(projection.s() > previousS, "s 应随里程递增");
            previousS = projection.s();
        }
    }

    @Test
    @DisplayName("偏出中心线 100 m 的点，|e| 应约 100 m，s 不变；正北行进时东侧为右（e>0）")
    void projectionOffset_shouldReportCrossTrackDistance() {
        // 用正北直线航道：这样"向东偏移"恰好垂直于中心线，投影距离可精确断言
        WaterwayGeometry straightNorth = new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(115.85, 28.60),
                new WaterwayGeometry.Vertex(115.85, 28.70)));

        double s = 1000.0;
        WaterwayGeometry.Vertex onLine = straightNorth.pointAt(s);
        double metersPerDegLon = WaterwayGeometry.METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(onLine.lat()));
        double offsetLon = onLine.lon() + 100.0 / metersPerDegLon;

        WaterwayGeometry.Projection projection = straightNorth.project(offsetLon, onLine.lat());

        assertEquals(100.0, projection.crossTrackDistanceM(), 2.0,
                "横向距离应约 100 m，实际 " + projection.crossTrackDistanceM());
        assertEquals(100.0, projection.e(), 2.0,
                "正北行进时东侧是右侧，故 e 为正，实际 " + projection.e());
        assertEquals(s, projection.s(), 2.0, "垂直偏移不应改变弧长");
    }

    @Test
    @DisplayName("反向投影：s 越界自动夹到 [0, 总长]")
    void pointAt_shouldClampOutOfRange() {
        WaterwayGeometry geo = ganjiangSegment();

        WaterwayGeometry.Vertex negative = geo.pointAt(-1000.0);
        assertEquals(CHAOYANG.lon(), negative.lon(), 1e-9);
        assertEquals(CHAOYANG.lat(), negative.lat(), 1e-9);

        WaterwayGeometry.Vertex beyond = geo.pointAt(geo.totalLengthM() + 1000.0);
        assertEquals(BAYI.lon(), beyond.lon(), 1e-9);
        assertEquals(BAYI.lat(), beyond.lat(), 1e-9);
    }

    @Test
    @DisplayName("入参校验：顶点不足 2 个 / 相邻顶点重合 → 抛异常（不静默兜底）")
    void constructor_shouldRejectInvalidVertices() {
        assertThrows(IllegalArgumentException.class,
                () -> new WaterwayGeometry(List.of(CHAOYANG)));
        assertThrows(IllegalArgumentException.class,
                () -> new WaterwayGeometry(List.of(CHAOYANG, new WaterwayGeometry.Vertex(115.852, 28.646))));
        assertThrows(IllegalArgumentException.class,
                () -> new WaterwayGeometry(List.of(new WaterwayGeometry.Vertex(200.0, 28.6), NANCHANG)));
    }

    @Test
    @DisplayName("弧长差：toS − fromS，负值表示逆向（双向航道要能表达方向）")
    void arcDistance_shouldBeSigned() {
        assertEquals(500.0, WaterwayGeometry.arcDistanceM(1000.0, 1500.0), 1e-9);
        assertEquals(-500.0, WaterwayGeometry.arcDistanceM(1500.0, 1000.0), 1e-9);
    }
}
