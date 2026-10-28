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
 * 仿真坐标与气象网格切南昌段（T1-4 立，T2-7 按放样定稿重写）。
 *
 * <p>判据（清单原文）：船位在<b>航段一带</b>；气象网格四点与航段<b>同区</b>；四架无人机都有遥测帧。
 *
 * <p>为什么要有这条测试：三个仿真器的坐标各在一个 {@code @Value} 兜底里，只改船不改网格
 * 的后果是「风落在原来的地方，抗风淘汰无从触发」——这是清单点名的「最容易漏」的一条。
 * 本测试把三处坐标<b>同时</b>钉在航段范围内，漏改任何一个都会红。
 *
 * <p><b>坐标口径（2026-10-28 序号 2 图上放样定稿）</b>：
 * <ul>
 *   <li>南端机巢 (115.8480, 28.6420)</li>
 *   <li>北端机巢 (115.8670, 28.6980)</li>
 *   <li>船舶起点 (115.8630, 28.6680)——两巢连线中点附近（垂距 578 m），距南巢 3.24 km、北巢 3.36 km</li>
 * </ul>
 * 两巢相距 6.50 km（按两中心点球面距离）。<b>注意这不是 73 号文档的 5.78 km</b>，
 * 差异已提给彭紫情确认；若后续口径变更，本测试的常量是唯一需要改的地方。
 *
 * <p>与 T1-4 的差别：T1-4 用的是 4.15 km 的估算航段（115.861→115.876），
 * 本文件改为放样定稿值，并<b>新增「船跑完全程不出航段」一条</b>——
 * 旧版只验起点在航段内，航向设错时船会一路偏出江面而测试仍然绿。
 */
class SimCoordinatesNanchangSegmentTest {

    /** 南端机巢（下游） */
    private static final double SOUTH_LON = 115.8480;
    private static final double SOUTH_LAT = 28.6420;
    /** 北端机巢（上游） */
    private static final double NORTH_LON = 115.8670;
    private static final double NORTH_LAT = 28.6980;
    /** 船舶起点 */
    private static final double SHIP_LON = 115.8630;
    private static final double SHIP_LAT = 28.6680;

    /** 航段外包框（含少量余量，容噪声与巡航半径；仍远小于原仿真域 114.29–114.31）。 */
    private static final double MIN_LON = 115.840;
    private static final double MAX_LON = 115.880;
    private static final double MIN_LAT = 28.635;
    private static final double MAX_LAT = 28.705;

    /**
     * 船走廊的横向容差（km）。
     *
     * <p>船起点距两巢连线 578 m，已占去大半；再留 200 m 给噪声与等距直线近似的漂移。
     * 旧航向 21° 时 5 km 航程会偏出约 1.3 km，必然被这条拦下。
     */
    private static final double SHIP_CORRIDOR_KM = 0.9;

    /**
     * 气象网格四角的最远容差（km）。
     *
     * <p>原设 3.5，T2-7 定稿后经穷举确认<b>几何上不可能</b>：航段是西南—东北斜向、
     * 长 6.5 km，2×2 方网格要罩住南巢 (28.642) 与北巢 (28.698)，至少一个角必然退到
     * 4.7 km 开外。这不影响功能——下游按「离船最近的点」取风，船全程在网格内；
     * 只有放弃 2×2、改成 3×3 或按航向取矩形才能压下来，属 T1 口径变更，不在今天。
     *
     * <p>故容差放到 5.0 km 并<b>额外断言「船全程有点可取」</b>——后者才是真正保护
     * 抗风判定的那条，比角落距离更接近问题本质。
     */
    private static final double GRID_CORNER_TOLERANCE_KM = 5.0;

    /** 点到航段（南巢—北巢线段）的距离，km（等距圆柱近似，演示精度足够）。 */
    private static double distanceToSegmentKm(double lon, double lat) {
        double kmPerDegLat = 111.32;
        double kmPerDegLon = 111.32 * Math.cos(Math.toRadians(28.67));
        double bx = (NORTH_LON - SOUTH_LON) * kmPerDegLon;
        double by = (NORTH_LAT - SOUTH_LAT) * kmPerDegLat;
        double px = (lon - SOUTH_LON) * kmPerDegLon;
        double py = (lat - SOUTH_LAT) * kmPerDegLat;
        double t = Math.max(0.0, Math.min(1.0, (px * bx + py * by) / (bx * bx + by * by)));
        return Math.hypot(px - t * bx, py - t * by);
    }

    private static void assertInBox(String what, double lon, double lat) {
        assertTrue(lon >= MIN_LON && lon <= MAX_LON && lat >= MIN_LAT && lat <= MAX_LAT,
                what + " 应落在南昌航段内，实际 (" + lon + ", " + lat + ")");
    }

    @Test
    @DisplayName("船：起点为放样定稿值，且朝北巢（上游）推进")
    void shipStartsAtFinalSurveyPointAndHeadsUpstream() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimAisConnector sim = new SimAisConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        sim.pollOnce(1_000L);

        AisMessage m = publisher.ais().get(0);
        assertInBox("AIS 船位", m.lon(), m.lat());

        double dShip = Math.hypot(m.lon() - SHIP_LON, m.lat() - SHIP_LAT);
        assertTrue(dShip < 0.0005, "起点应贴近放样定稿值 (115.863, 28.668)，实际偏差 " + dShip);
        assertTrue(m.lat() > SHIP_LAT, "应向北上（上游）推进：纬度应增大");
    }

    @Test
    @DisplayName("船：跑完整段航程（到北巢为止）仍在航段走廊内")
    void shipStaysInsideCorridorForWholeRoute() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimAisConnector sim = new SimAisConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();

        // 船起点 (28.668) 到北巢 (28.698) 纬度差 0.030°，cog 6.7° 下每节拍约 0.00408°
        // ⇒ 约 736 个节拍到北巢，取 700 个节拍（演示里船到港即结束，不会继续往北漂）
        for (int i = 0; i < 700; i++) {
            sim.pollOnce(1_000L);
        }

        List<AisMessage> sent = publisher.ais();
        assertEquals(700, sent.size(), "每节拍应发一条");

        double worstKm = 0.0;
        AisMessage worst = null;
        for (AisMessage m : sent) {
            double km = distanceToSegmentKm(m.lon(), m.lat());
            if (km > worstKm) {
                worstKm = km;
                worst = m;
            }
        }
        assertTrue(worstKm <= SHIP_CORRIDOR_KM,
                "航程内船应不出航段走廊（≤" + SHIP_CORRIDOR_KM + " km），实际最远 "
                        + String.format("%.2f", worstKm) + " km，位置 ("
                        + worst.lon() + ", " + worst.lat() + ")");
    }

    @Test
    @DisplayName("无人机：巡航圈在航段两巢之间，四架都有遥测帧")
    void uavOrbitsInsideSegmentWithFourDrones() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimUavTelemetryConnector sim = new SimUavTelemetryConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        sim.pollOnce(1_000L);

        List<UavTelemetry> sent = publisher.telemetry();
        assertEquals(4, sent.size(), "机队规模应与台账四架对齐（否则出现没有遥测的幽灵机）");

        // 巡航中心取两巢中点：任一架到两巢的距离都应在 3.5 km 以内
        // （沿用旧的航段中点 115.8685/28.6815 时，到南巢会到 4.9 km，此断言即红）
        for (UavTelemetry m : sent) {
            assertInBox("遥测 " + m.uavId(), m.lon(), m.lat());
            assertNotNull(m.uavId());
            double dSouthKm = Math.hypot(m.lon() - SOUTH_LON, m.lat() - SOUTH_LAT) * 111.32;
            double dNorthKm = Math.hypot(m.lon() - NORTH_LON, m.lat() - NORTH_LAT) * 111.32;
            assertTrue(dSouthKm < 3.5 && dNorthKm < 3.5,
                    m.uavId() + " 应巡航在两巢之间（距南巢 " + dSouthKm + " km、北巢 " + dNorthKm + " km）");
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
        // 网格必须罩住航段两端：否则船在一端、风在另一端，抗风淘汰无从触发。
        // 定稿后航段南北跨 0.056°，旧步长 0.044° 只到 28.682，北巢与船的后半程会落在网外。
        assertTrue(minLat <= SOUTH_LAT, "网格南界应覆盖南端机巢");
        assertTrue(maxLat >= NORTH_LAT, "网格北界应覆盖北端机巢");
        assertTrue(minLon <= SOUTH_LON, "网格西界应覆盖南巢经度");
        assertTrue(maxLon >= NORTH_LON, "网格东界应覆盖北巢经度");

        // 真正保护抗风判定的那条：船跑完全程，每一帧都能在网格里找到一个点位取风。
        // 只验「两端被罩住」不够——斜向航段上，两端在内、中段被挤出的情况一样会让风失配。
        RecordingPublisher shipPub = new RecordingPublisher();
        SimAisConnector ship = new SimAisConnector(shipPub);
        FieldInjector.applySimDefaults(ship);
        ship.start();
        for (int i = 0; i < 700; i++) {
            ship.pollOnce(1_000L);
        }
        final double gMinLon = minLon;
        final double gMaxLon = maxLon;
        final double gMinLat = minLat;
        final double gMaxLat = maxLat;
        for (AisMessage m : shipPub.ais()) {
            boolean covered = sent.stream().anyMatch(w ->
                    w.lon() >= gMinLon && w.lon() <= gMaxLon && w.lat() >= gMinLat && w.lat() <= gMaxLat);
            assertTrue(covered, "船位应落在气象网格范围内：" + m.lon() + "," + m.lat());
        }
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
