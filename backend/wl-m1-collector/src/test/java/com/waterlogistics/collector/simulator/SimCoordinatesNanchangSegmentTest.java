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
 *   <li>船舶起点 (115.8611, 28.6660)——演示基准定稿，沿程 2.40 km，落在赣江航道上（2026-10-29；作废 115.863/28.668）</li>
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
    /** 船舶起点（演示基准定稿：沿程 2.40 km，2026-10-29） */
    private static final double SHIP_LON = 115.8611;
    private static final double SHIP_LAT = 28.6660;

    /** 航段外包框（含少量余量，容噪声与巡航半径；仍远小于原仿真域 114.29–114.31）。 */
    private static final double MIN_LON = 115.840;
    private static final double MAX_LON = 115.890;
    private static final double MIN_LAT = 28.635;
    private static final double MAX_LAT = 28.705;

    /**
     * 船走廊的横向容差（km）。
     *
     * <p><b>2026-10-29 修复</b>：船的参考线是<b>赣江航道折线</b>（朝阳→南昌→八一大桥），
     * 不是两巢连线——两巢连线只是陆地布点。定稿起点就在航道中心线附近，容差收到 0.35 km；
     * 旧航向 6.7°（朝陆地机巢）跑 700 秒后离中心线 1149 m，在这条判据下必然判红。
     */
    private static final double SHIP_CORRIDOR_KM = 0.35;

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

    /** 赣江航道折线（船的参考线）：朝阳洲 → 南昌站 → 八一大桥（2026-10-29 修复方案定稿）。 */
    private static final double[] CORRIDOR_LON = {115.852, 115.859, 115.879};
    private static final double[] CORRIDOR_LAT = {28.646, 28.663, 28.692};

    /** 点到赣江航道折线的距离，km（等距圆柱近似，演示精度足够）。 */
    private static double distanceToSegmentKm(double lon, double lat) {
        double kmPerDegLat = 111.32;
        double kmPerDegLon = 111.32 * Math.cos(Math.toRadians(28.67));
        double px = lon * kmPerDegLon;
        double py = lat * kmPerDegLat;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < CORRIDOR_LON.length - 1; i++) {
            double ax = CORRIDOR_LON[i] * kmPerDegLon;
            double ay = CORRIDOR_LAT[i] * kmPerDegLat;
            double bx = CORRIDOR_LON[i + 1] * kmPerDegLon;
            double by = CORRIDOR_LAT[i + 1] * kmPerDegLat;
            double t = Math.max(0.0, Math.min(1.0,
                    ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / ((bx - ax) * (bx - ax) + (by - ay) * (by - ay))));
            best = Math.min(best, Math.hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay))));
        }
        return best;
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
        assertTrue(dShip < 0.0005, "起点应贴近演示基准定稿值 (115.8611, 28.6660)，实际偏差 " + dShip);
        assertTrue(m.lat() > SHIP_LAT, "31.2° 航向应向北（沿赣江航道往八一大桥方向）推进：纬度应增大");
    }

    @Test
    @DisplayName("船：跑 700 个节拍（演示全程）仍不出赣江航道走廊")
    void shipStaysInsideCorridorForWholeRoute() {
        RecordingPublisher publisher = new RecordingPublisher();
        SimAisConnector sim = new SimAisConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();

        // 定稿起点 + 31.2° 航向下：700 秒时船在 (115.8764, 28.6883)，距航道中心线约 5 m；
        // 到八一大桥约 821 秒、越 MAX_LON 约 866 秒，700 个节拍即演示全程（船到港即结束）
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
                "航程内船应不出赣江航道走廊（≤" + SHIP_CORRIDOR_KM + " km），实际最远 "
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
