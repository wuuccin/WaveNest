package com.waterlogistics.collector.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T1-4（10-27 任务清单）：仿真坐标与气象网格切南昌段。
 *
 * <p>判据（清单原文）：船位在<b>航段一带</b>；气象网格四点与航段<b>同区</b>；四架无人机都有遥测帧。
 *
 * <p>为什么要有这条测试：三个仿真器的坐标各在一个 {@code @Value} 兜底里，只改船不改网格
 * 的后果是「风落在原来的地方，抗风淘汰无从触发」——这是清单点名的「最容易漏」的一条。
 * 本测试把三处坐标<b>同时</b>钉在航段范围内，漏改任何一个都会红。
 *
 * <p>航段口径：朝阳端 (115.8610, 28.6640) → 八一端 (115.8760, 28.6990)，约 4.15 km，
 * 与《五日总计划》第五节「两巢两端分置、相距约 4.14 km」一致。
 */
class SimCoordinatesNanchangSegmentTest {

    /** 朝阳端（**/
    private static final double CHAOYANG_LON = 115.8610;
    private static final double CHAOYANG_LAT = 28.6640;
    /** 八一端 */
    private static final double BAYI_LON = 115.8760;
    private static final double BAYI_LAT = 28.6990;

    /** 航段外包框（含少量余量，容噪声与巡航半径；仍远小于原仿真域 114.29–114.31）。 */
    private static final double MIN_LON = 115.855;
    private static final double MAX_LON = 115.885;
    private static final double MIN_LAT = 28.658;
    private static final double MAX_LAT = 28.705;

    /** 气象网格是外包矩形，四角会超出航段两端，但须贴住航段走廊（不超过 3.5 km）。 */
    private static final double GRID_CORNER_TOLERANCE_KM = 3.5;

    /** 点到航段（朝阳端—八一端线段）的距离，km（等距圆柱近似，演示精度足够）。 */
    private static double distanceToSegmentKm(double lon, double lat) {
        double kmPerDegLat = 111.32;
        double kmPerDegLon = 111.32 * Math.cos(Math.toRadians(28.68));
        double ax = 0.0;
        double ay = 0.0;
        double bx = (BAYI_LON - CHAOYANG_LON) * kmPerDegLon;
        double by = (BAYI_LAT - CHAOYANG_LAT) * kmPerDegLat;
        double px = (lon - CHAOYANG_LON) * kmPerDegLon;
        double py = (lat - CHAOYANG_LAT) * kmPerDegLat;
        double dx = bx - ax;
        double dy = by - ay;
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
        double cx = ax + t * dx;
        double cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    private static void assertInSegment(String what, double lon, double lat) {
        assertTrue(lon >= MIN_LON && lon <= MAX_LON && lat >= MIN_LAT && lat <= MAX_LAT,
                what + " 应落在南昌航段内，实际 (" + lon + ", " + lat + ")");
    }

    @Test
    @DisplayName("船：起点在南昌航段朝阳端，且朝八一端（上游）推进")
    void shipStartsAtChaoyangEndAndHeadsUpstream() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimAisConnector sim = new SimAisConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        sim.pollOnce(1_000L);

        AisMessage m = publisher.ais().get(0);
        assertInSegment("AIS 船位", m.lon(), m.lat());

        double dBayi = Math.hypot(m.lon() - BAYI_LON, m.lat() - BAYI_LAT);
        double dChaoyang = Math.hypot(m.lon() - CHAOYANG_LON, m.lat() - CHAOYANG_LAT);
        assertTrue(dChaoyang < 0.001, "起点应贴近朝阳端，实际距朝阳端 " + dChaoyang);
        assertTrue(dBayi > 0.01, "起点不应在八一端（应从朝阳端出发）");
        // 朝上游＝朝八一方向推进：纬度增大
        assertTrue(m.lat() > CHAOYANG_LAT, "应向八一端（纬度增大方向）推进");
    }

    @Test
    @DisplayName("无人机：巡航圈在航段内，四架都有遥测帧")
    void uavOrbitsInsideSegmentWithFourDrones() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimUavTelemetryConnector sim = new SimUavTelemetryConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        sim.pollOnce(1_000L);

        List<UavTelemetry> sent = publisher.telemetry();
        assertEquals(4, sent.size(), "机队规模应与台账四架对齐（否则出现没有遥测的幽灵机）");
        for (UavTelemetry m : sent) {
            assertInSegment("遥测 " + m.uavId(), m.lon(), m.lat());
            assertNotNull(m.uavId());
        }
    }

    @Test
    @DisplayName("气象：网格四点与航段同区，且覆盖航段南北两端")
    void weatherGridCoversSegment() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimWeatherConnector sim = new SimWeatherConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        sim.pollOnce(1_000L);

        List<WeatherData> sent = publisher.weather();
        assertEquals(4, sent.size(), "2×2 网格，U-05 甲案：按位置多条");

        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (WeatherData w : sent) {
            double dKm = distanceToSegmentKm(w.lon(), w.lat());
            assertTrue(dKm <= GRID_CORNER_TOLERANCE_KM,
                    "气象网格点应贴近航段走廊（≤" + GRID_CORNER_TOLERANCE_KM + " km），实际 ("
                            + w.lon() + ", " + w.lat() + ") 距航段 " + dKm + " km");
            minLat = Math.min(minLat, w.lat());
            maxLat = Math.max(maxLat, w.lat());
            minLon = Math.min(minLon, w.lon());
            maxLon = Math.max(maxLon, w.lon());
        }
        // 网格必须罩住航段两端：否则船在一端、风在另一端，抗风淘汰无从触发
        assertTrue(minLat <= CHAOYANG_LAT, "网格南界应覆盖朝阳端");
        assertTrue(maxLat >= BAYI_LAT, "网格北界应覆盖八一端");
        assertTrue(minLon <= CHAOYANG_LON, "网格西界应覆盖朝阳端经度");
        assertTrue(maxLon >= BAYI_LON, "网格东界应覆盖八一端经度");
    }

    @Test
    @DisplayName("三处坐标同属南昌段：不再残留旧仿真域（114.29–114.31）")
    void noLeftoverOldDomainCoordinates() {
        RecordingPublisher aisPub = new RecordingPublisher();
        SimAisConnector ais = new SimAisConnector(aisPub);
        FieldInjector.applySimDefaults(ais);
        ais.start();
        ais.pollOnce(1_000L);

        RecordingPublisher wxPub = new RecordingPublisher();
        SimWeatherConnector wx = new SimWeatherConnector(wxPub);
        FieldInjector.applySimDefaults(wx);
        wx.start();
        wx.pollOnce(1_000L);

        for (AisMessage m : aisPub.ais()) {
            assertTrue(m.lon() > 115.0, "船位仍在旧仿真域：" + m.lon());
        }
        for (WeatherData w : wxPub.weather()) {
            assertTrue(w.lon() > 115.0, "气象网格仍在旧仿真域：" + w.lon());
        }
        assertNull(wx.currentBias(), "未注入时不应有偏置");
    }
}
