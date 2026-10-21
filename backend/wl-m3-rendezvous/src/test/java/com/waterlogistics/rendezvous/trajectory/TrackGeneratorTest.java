package com.waterlogistics.rendezvous.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 航迹生成单测——手算对照（时长／端点／铺点）＋三段拼接与分段标签
 * （《43-编码分工说明》§6.3、《33-M3-路径规划》§3.1）。
 *
 * <p>几何夹具：同纬线直线，距离用与 {@code GeoApprox} 同一近似手算——
 * 全部期望值为精确值，容差只吃浮点末位。
 */
class TrackGeneratorTest {

    /** 作业纬线 */
    private static final double LAT0 = 30.0;

    /** 起点经度 */
    private static final double LON0 = 121.0;

    /** 起飞时刻 */
    private static final long T0 = 1_700_000_000_000L;

    // ===== 单段生成 =====

    /**
     * 3000 m @ 15 m/s → τ_fine＝200 s；5 个航点含端点、时间升序、字段照注入值。
     */
    @Test
    void generate_shouldMeetHandCalculatedDurationAndEndpoints() {
        B_FlyableRoute route = TrackGenerator.generate(new GenerateRouteCommand(
                "combo-1", "RT-1", lonAt(0), LAT0, lonAt(3_000), LAT0,
                60.0, 15.0, T0, 5,
                List.of("LP-1", "LP-2"), List.of("AZ-7"), 0.05, 0.2));

        assertEquals(200, route.tauFine(), "精算时长＝3000/15＝200 s");
        assertEquals(5, route.waypoints().size(), "航点数＝注入值");

        Waypoint first = route.waypoints().get(0);
        Waypoint last = route.waypoints().get(4);
        assertEquals(lonAt(0), first.lon(), 1e-9, "首点经度＝起点");
        assertEquals(T0, first.t(), "首点时刻＝起点时刻");
        assertEquals(lonAt(3_000), last.lon(), 1e-9, "末点经度＝终点");
        assertEquals(T0 + 200_000L, last.t(), "末点时刻＝起点＋τ_fine");

        assertEquals(lonAt(1_500), route.waypoints().get(2).lon(), 1e-9, "中点＝半程（均匀铺点）");
        assertEquals(60.0, first.altM(), 1e-9, "巡航高度照注入值");
        assertEquals(15.0, first.speedMs(), 1e-9, "分段速度照注入值");

        for (int i = 1; i < route.waypoints().size(); i++) {
            assertTrue(route.waypoints().get(i).t() > route.waypoints().get(i - 1).t(),
                    "航点时间严格升序");
        }

        assertEquals(10.0, route.energyExact(), 1e-9, "精确能耗＝τ×率（200×0.05%）");
        assertEquals(0.2, route.pathRisk(), 1e-9, "路径风险照注入值");
        assertEquals(List.of("LP-1", "LP-2"), route.landingPointsAlong(), "迫降点标注透传");
        assertEquals(List.of("AZ-7"), route.avoidZones(), "避让标识透传");
        assertTrue(route.routeVersion().startsWith("RV-RT-1-" + T0), "版本＝routeId＋起点时刻＋几何指纹确定性生成");
        assertEquals("combo-1", route.comboId());
    }

    /** 入参校验：航点数 <2、速度 ≤0 一律拒绝。 */
    @Test
    void generate_shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> TrackGenerator.generate(
                new GenerateRouteCommand("combo-1", "RT-x", lonAt(0), LAT0, lonAt(3_000), LAT0,
                        60.0, 15.0, T0, 1, List.of(), List.of(), 0, 0)),
                "航点数不足 2 应拒绝");
        assertThrows(IllegalArgumentException.class, () -> TrackGenerator.generate(
                new GenerateRouteCommand("combo-1", "RT-x", lonAt(0), LAT0, lonAt(3_000), LAT0,
                        60.0, 0.0, T0, 5, List.of(), List.of(), 0, 0)),
                "速度非正应拒绝");
    }

    /** 短腿钳制：距离小于 speed/2 时 Math.round 会塌缩 0——时长须钳到 ≥1 s（时间仍严格递增）。 */
    @Test
    void generate_shouldClampTinyLegToOneSecond() {
        B_FlyableRoute route = TrackGenerator.generate(new GenerateRouteCommand(
                "combo-1", "RT-tiny", lonAt(0), LAT0, lonAt(0.5), LAT0,
                60.0, 15.0, T0, 5, List.of(), List.of(), 0.05, 0.0));

        assertEquals(1, route.tauFine(), "0.033 s 四舍五入到 0 → 钳到 1 s");
        assertEquals(T0, route.waypoints().get(0).t(), "首点＝起点时刻");
        assertEquals(T0 + 1_000L, route.waypoints().get(4).t(), "末点＝起点＋1 s");
        for (int i = 1; i < route.waypoints().size(); i++) {
            assertTrue(route.waypoints().get(i).t() > route.waypoints().get(i - 1).t(), "时间仍严格递增");
        }
        assertEquals(1.0 * 0.05, route.energyExact(), 1e-9, "能耗按整秒时长计（钳制后 1 s × 0.05%/s）");
    }

    /** 分段口径词唯一：fromValue 首个匹配即返回，重复会导致解析错段。 */
    @Test
    void segments_shouldHaveUniqueWireValues() {
        assertEquals(TrackSegment.values().length,
                java.util.Arrays.stream(TrackSegment.values()).map(TrackSegment::getValue).distinct().count(),
                "分段口径词须唯一");
    }

    // ===== 三段任务航迹 =====

    /**
     * 三段拼接：去程 3000 m@15＝200 s、末段 300 m@3＝100 s、返程 3300 m@15＝220 s；
     * 7 点（段边界去重）、标签 3／2／2、时间自起飞时刻顺延、端点与分段边界逐一对位。
     */
    @Test
    void generateMissionTrack_shouldStitchThreeSegmentsWithLabels() {
        SegmentedTrack track = TrackGenerator.generateMissionTrack(new MissionTrackCommand(
                "combo-1", "MT-1",
                lonAt(0), LAT0,
                lonAt(3_000), LAT0,
                lonAt(3_300), LAT0,
                lonAt(0), LAT0,
                60.0, 15.0, 3.0, 15.0,
                T0, 3));

        assertEquals(7, track.points().size(), "3 段拼接去重边界：3×3−2");

        // 分段标签：去程 3 点；末段交接与返程各 2 点（首点与上一段末点重复，已去重）
        for (int i = 0; i < 3; i++) {
            assertEquals(TrackSegment.OUTBOUND, track.points().get(i).segment());
        }
        for (int i = 0; i < 2; i++) {
            assertEquals(TrackSegment.TERMINAL, track.points().get(3 + i).segment());
            assertEquals(TrackSegment.RETURN, track.points().get(5 + i).segment());
        }
        assertEquals("去程", TrackSegment.OUTBOUND.getValue(), "分段词（《43》三段表述）");
        assertEquals("末段交接", TrackSegment.TERMINAL.getValue());
        assertEquals("返程", TrackSegment.RETURN.getValue());

        // 端点与边界：巢 → 会面点 → 交接完成位置 → 巢
        assertEquals(lonAt(0), track.points().get(0).waypoint().lon(), 1e-9, "首点＝机巢");
        assertEquals(lonAt(3_000), track.points().get(2).waypoint().lon(), 1e-9, "去程终点＝会面点");
        assertEquals(lonAt(3_300), track.points().get(4).waypoint().lon(), 1e-9, "末段终点＝交接完成位置");
        assertEquals(lonAt(0), track.points().get(6).waypoint().lon(), 1e-9, "返程终点＝归巢落点");

        // 时间顺延：段时长 200 s → 100 s → 220 s
        assertEquals(T0, track.points().get(0).waypoint().t(), "第一点＝起飞时刻");
        assertEquals(T0 + 200_000L, track.points().get(2).waypoint().t(), "去程末点＝200 s");
        assertEquals(T0 + 250_000L, track.points().get(3).waypoint().t(), "末段次点＝250 s");
        assertEquals(T0 + 300_000L, track.points().get(4).waypoint().t(), "末段末点＝300 s");
        assertEquals(T0 + 410_000L, track.points().get(5).waypoint().t(), "返程次点＝410 s");
        assertEquals(T0 + 520_000L, track.points().get(6).waypoint().t(), "收官＝520 s");

        for (int i = 1; i < track.points().size(); i++) {
            assertTrue(track.points().get(i).waypoint().t() >= track.points().get(i - 1).waypoint().t(),
                    "整体时间单调不减");
        }
    }

    // ===== 夹具 =====

    /** 以东向米偏移量构造经度（与 GeoApprox 同一近似） */
    private static double lonAt(double eastM) {
        return LON0 + eastM / mPerDegLon(LAT0);
    }

    private static double mPerDeg() {
        return Math.PI * 6_371_000.0 / 180.0;
    }

    private static double mPerDegLon(double lat) {
        return mPerDeg() * Math.cos(Math.toRadians(lat));
    }
}
