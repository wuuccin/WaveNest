package com.waterlogistics.dispatch.homecoming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.dispatch.prediction.CenterlineLedger;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.EmergencyLandingPointEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.EmergencyLandingPointMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 11-16 序号 3-1：第三级迫降点的<b>模块接线</b>——台账 → 候选点（距航道垂距、水陆）→ 选择器 → 航迹下发。
 *
 * <p>算法层已于 2-2 落地（{@code EmergencyLandingSelector} 只消费注入候选），本类验的是它上游那一段：
 * <ol>
 *   <li>候选点由本层从 {@code emergency_landing_point} 组装，距航道垂距由中心线投影算出；</li>
 *   <li>水陆主选取 {@code surface_type} 列，列为空时按 1-6 兜底用江面半宽 250 m 纯几何判；</li>
 *   <li>阈值读 {@code e10LandingCorridorHalfWidthM}，<b>缺键不回落 500</b>，而是让候选全部判不可算；</li>
 *   <li>拿到标识与坐标即走既有 {@code generateTrack} 下发；拿不到就不下发、不臆造。</li>
 * </ol>
 *
 * <p>用 mock 替身而非连库：本类要验的是「装配与判定顺序」，不是 SQL；连库会把这些判据绑在灌库状态上。
 */
class HomecomingLandingWiringTest {

    private static final String HOME_NEST = "NEST-HOME";
    private static final String ORDER_ID = "ORDER-1";
    private static final String DRONE_ID = "DRONE-1";
    private static final long ARRIVAL_ETA_MS = 1_000L;
    private static final long SLOT_HOLD_MS = 2_000L;

    /** 中心线：正南北一条直线，纬向 0.02°（约 2.2 km），用作横偏基准。 */
    private static final double CENTER_LON = 120.15;
    private static final double CENTER_LAT = 30.28;

    /** 起点：贴着中心线略偏，保证「起终点不重合」能生成航迹。 */
    private static final GeoPoint START = new GeoPoint(120.1505, 30.2810);

    /** 1° 经度在 30.28°N 处约 96 km，故 0.001° ≈ 96 m。 */
    private static final double DEG_LON_PER_96M = 0.001;

    // ───────────────────────── 夹具 ─────────────────────────

    private final OccupancySlotService slotService = mock(OccupancySlotService.class);
    private final NestMapper nestMapper = mock(NestMapper.class);
    private final DroneMapper droneMapper = mock(DroneMapper.class);
    private final OrderTaskMapper orderTaskMapper = mock(OrderTaskMapper.class);
    private final TaskLogMapper taskLogMapper = mock(TaskLogMapper.class);
    private final SysParamLoader sysParamLoader = mock(SysParamLoader.class);
    private final EmergencyLandingPointMapper landingPointMapper = mock(EmergencyLandingPointMapper.class);
    private final CenterlineLedger centerlineLedger = mock(CenterlineLedger.class);

    /** 捕获下发的航迹命令；为空即「未下发」。 */
    private final AtomicReference<GenerateRouteCommand> dispatched = new AtomicReference<>();

    private HomecomingService service() {
        when(nestMapper.selectById(HOME_NEST)).thenReturn(homeNest());
        when(nestMapper.selectList(any())).thenReturn(List.of());
        when(droneMapper.selectById(anyString())).thenReturn((DroneEntity) null);
        when(orderTaskMapper.selectById(anyString())).thenReturn((OrderTaskEntity) null);
        when(taskLogMapper.insert(any(TaskLogEntity.class))).thenReturn(1);
        when(centerlineLedger.load()).thenReturn(centerline());
        HomecomingTrackPort port = command -> {
            dispatched.set(command);
            return new B_FlyableRoute("RTN-" + ORDER_ID, "1", ORDER_ID,
                    List.of(new Waypoint(command.startLon(), command.startLat(), 30.0, 12.0, 0L),
                            new Waypoint(command.endLon(), command.endLat(), 30.0, 12.0, 120_000L)),
                    120, 1.5, 0.1, List.of(), List.of());
        };
        return new HomecomingService(slotService, nestMapper, droneMapper, orderTaskMapper,
                taskLogMapper, sysParamLoader, port, landingPointMapper, centerlineLedger);
    }

    private static NestEntity homeNest() {
        NestEntity nest = new NestEntity();
        nest.setNestId(HOME_NEST);
        nest.setLon(BigDecimal.valueOf(CENTER_LON));
        nest.setLat(BigDecimal.valueOf(CENTER_LAT + 0.02));
        nest.setCapacity(4);
        nest.setStatus("可用");
        return nest;
    }

    private static WaterwayGeometry centerline() {
        return new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(CENTER_LON, CENTER_LAT),
                new WaterwayGeometry.Vertex(CENTER_LON, CENTER_LAT + 0.02)));
    }

    private static HomecomingInput input() {
        // 电量 30%：低于「基线 40% + 裕度 20%」，第一级不可回；候选他巢给空，故落到第三级
        // cargoOnboard=false：本类只验落点接线，不走「有货迫降 → 订单置失败」那条分支
        return new HomecomingInput("EV-1", "ABORT", "TASK-1", ORDER_ID, DRONE_ID, HOME_NEST,
                START, 30.0, 40.0, false, null, null, "LOW_BATTERY", 1_700_000_000_000L,
                Boolean.TRUE);
    }

    private static HomecomingParams params() {
        return new HomecomingParams(20.0, 20.0, null, 0.8, 1.0, 1.0, 0L, ARRIVAL_ETA_MS, SLOT_HOLD_MS);
    }

    private static EmergencyLandingPointEntity landingPoint(String elpId, double lonOffsetDeg,
            String surfaceType, String status) {
        EmergencyLandingPointEntity row = new EmergencyLandingPointEntity();
        row.setElpId(elpId);
        row.setLon(BigDecimal.valueOf(CENTER_LON + lonOffsetDeg));
        row.setLat(BigDecimal.valueOf(CENTER_LAT + 0.010));
        row.setStatus(status);
        row.setSurfaceType(surfaceType);
        row.setCapacity(1);
        return row;
    }

    // ───────────────────────── 用例 ─────────────────────────

    @Test
    @DisplayName("★ 3-1：走廊内水面迫降点 ⇒ 第三级给出标识与坐标并走 generateTrack 下发")
    void thirdTier_waterPointInCorridor_shouldDispatchTrack() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn("500");
        when(landingPointMapper.selectList(any()))
                .thenReturn(List.of(landingPoint("ELP-01", DEG_LON_PER_96M, "水面", "可用")));

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, decision.tier(), "电量与前两级候选都不允许回巢");
        assertEquals("ELP-01", decision.targetId(), "台账选出的迫降点标识要回填到结论");
        assertNotNull(decision.targetPosition(), "标识与坐标必须同时带出，缺一不下发");
        GenerateRouteCommand command = dispatched.get();
        assertNotNull(command, "有可用迫降点就必须下发返航航迹");
        assertEquals(decision.targetPosition().lon(), command.endLon(), 1e-9);
        assertEquals(decision.targetPosition().lat(), command.endLat(), 1e-9);
    }

    @Test
    @DisplayName("★ 3-1：陆地迫降点被否决 ⇒ 不下发、targetId 留空（不臆造落点）")
    void thirdTier_landPoint_shouldNotBeSelected() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn("500");
        when(landingPointMapper.selectList(any()))
                .thenReturn(List.of(landingPoint("ELP-LAND", DEG_LON_PER_96M, "陆地", "可用")));

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, decision.tier());
        assertNull(decision.targetId(), "陆地不可迫降，标识必须留空");
        assertNull(decision.targetPosition());
        assertNull(dispatched.get(), "无可用点不得下发航迹");
        assertTrue(decision.reason().contains("无可用迫降点"), "否决原因要能落审计：" + decision.reason());
    }

    @Test
    @DisplayName("★ 3-1：surface_type 为空时按江面半宽 250 m 兜底——96 m 判水面、385 m 判陆地")
    void thirdTier_withoutSurfaceColumn_shouldFallBackToRiverHalfWidth() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn("500");
        // 96 m：在 500 m 走廊内且 ≤250 m，兜底判水面；385 m：走廊内但 >250 m，兜底判陆地
        when(landingPointMapper.selectList(any())).thenReturn(List.of(
                landingPoint("ELP-FAR", DEG_LON_PER_96M * 4, null, "可用"),
                landingPoint("ELP-NEAR", DEG_LON_PER_96M, null, "可用")));

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertEquals("ELP-NEAR", decision.targetId(),
                "兜底判水陆应按垂距序筛出近岸水下点；选中 " + decision.targetId());
        assertNotNull(dispatched.get(), "兜底判为水面即照常下发");
    }

    @Test
    @DisplayName("★ 3-1：缺 e10LandingCorridorHalfWidthM ⇒ 候选全判不可算，不回落 500、不下发")
    void thirdTier_missingCorridorParam_shouldNotFabricateSelection() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn(null);
        when(landingPointMapper.selectList(any()))
                .thenReturn(List.of(landingPoint("ELP-01", DEG_LON_PER_96M, "水面", "可用")));

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertNull(decision.targetId(), "阈值没灌就不能按 500 猜，必须留空");
        assertNull(dispatched.get(), "不可算的结论不下发");
        assertTrue(decision.reason().contains("无可用迫降点"), "要留下「否决过」的痕迹：" + decision.reason());
    }

    @Test
    @DisplayName("★ 3-1：非「可用」状态的迫降点被淘汰")
    void thirdTier_maintenancePoint_shouldBeRejected() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn("500");
        when(landingPointMapper.selectList(any()))
                .thenReturn(List.of(landingPoint("ELP-MT", DEG_LON_PER_96M, "水面", "维护")));

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertNull(decision.targetId(), "维护中的点不能迫降");
        assertNull(dispatched.get());
    }

    @Test
    @DisplayName("★ 3-1：台账为空时第三级维持两态退化，不阻断判定")
    void thirdTier_emptyLedger_shouldDegradeWithoutThrowing() {
        when(sysParamLoader.get("e10LandingCorridorHalfWidthM")).thenReturn("500");
        when(landingPointMapper.selectList(any())).thenReturn(new ArrayList<>());

        HomecomingDecision decision = service().reviewAndDecide(input(), params(), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, decision.tier());
        assertNull(decision.targetId());
        assertNull(dispatched.get());
        verify(slotService, never()).tryReserve(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("★ 3-1：执行前置——第三级须标识与坐标同时具备，其余级别只要有标识")
    void isExecutable_shouldRequirePositionForLandingTier() {
        GeoPoint point = new GeoPoint(CENTER_LON, CENTER_LAT);
        assertTrue(HomecomingService.isExecutable(new HomecomingDecision(
                HomecomingTier.EMERGENCY_LAND_FIELD, "ELP-01", point, false, List.of(), List.of(), "有标识有坐标")));
        assertFalse(HomecomingService.isExecutable(new HomecomingDecision(
                HomecomingTier.EMERGENCY_LAND_FIELD, "ELP-01", null, false, List.of(), List.of(), "有标识无坐标")),
                "只有标识无坐标时航迹会跳过，但无人机状态仍会被置「迫降」——必须在执行前拦下");
        assertTrue(HomecomingService.isExecutable(new HomecomingDecision(
                HomecomingTier.RETURN_HOME_NEST, HOME_NEST, null, false, List.of(), List.of(), "回原巢无坐标字段")),
                "第一级坐标取自原巢台账，结论体本就不带 position");
        assertFalse(HomecomingService.isExecutable(new HomecomingDecision(
                HomecomingTier.FORCE_STOP_OTHER_NEST, "  ", null, false, List.of(), List.of(), "空标识")),
                "空白标识等于没给落点，照样不下发");
    }
}
