package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import com.waterlogistics.contract.rest.CandidateSnapshotView;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner;
import com.waterlogistics.storage.entity.CandidateSnapshotEntity;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.CandidateSnapshotMapper;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.SysParamMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选快照留痕（11-19 序号 3-1）单测：九项字段、择优键口径、失败 fail-soft、按订单回查。
 *
 * <p>本测试<b>不覆盖 task_log</b>——服务不依赖任何 task_log 入口，
 * 结构上就不可能写出候选行（见类注释）。
 */
class CandidateSnapshotServiceTest {

    private static final String ORDER_ID = "ORD-CAND-1";

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final long NOW_MS = 1_000_000L;

    private final List<CandidateSnapshotEntity> written = new ArrayList<>();

    private CandidateSnapshotService service;

    private boolean insertFails;

    @BeforeEach
    void setUp() {
        written.clear();
        insertFails = false;
        service = new CandidateSnapshotService(
                inMemorySnapshotMapper(), fixedSysParamLoader(), stubDroneMapper());
    }

    @Test
    @DisplayName("★ 九项落库：中选带会面时刻，落选只有原因；择优键按延误→距离两级")
    void record_shouldWriteNineFields() {
        CandidateGenerator.CandidateCombo nearSlow = combo("C-1", "N-1", "D-1", 1.0, 10.0);
        CandidateGenerator.CandidateCombo farFast = combo("C-2", "N-2", "D-2", 2.0, 20.0);

        CandidateFeasibilityPlanner.FeasibleSelection selection =
                new CandidateFeasibilityPlanner.FeasibleSelection(farFast, List.of(
                        verdict(nearSlow, "电量不足", true),
                        verdict(farFast, null, false)));

        int rows = service.record(order(), selection, committedMeetTime(), "PV-9", NOW_MS);

        assertEquals(2, rows);
        assertEquals(2, written.size());

        CandidateSnapshotEntity near = written.get(0);
        assertEquals(ORDER_ID, near.getOrderId());
        assertEquals("M30T", near.getModel());
        assertEquals("N-1", near.getNestId());
        assertEquals("D-1", near.getDroneId());
        assertEquals(0, new BigDecimal("1.0").compareTo(near.getDistKm()));
        assertNull(near.getMeetTime(), "落选候选未进入会面求解，不得用转场 ETA 冒充会面时刻");
        assertEquals("电量不足", near.getRejectReason());
        // now=1_000_000，deadline=1_060_000；1 km / 10 m/s = 100 000 ms → 延误 40 000 ms
        assertEquals("delay=40000.0;dist=1.00", near.getRankKey());
        assertEquals("PV-9", near.getSnapshotVersion());

        CandidateSnapshotEntity far = written.get(1);
        assertEquals(CandidateSnapshotService.CHOSEN_REASON, far.getRejectReason());
        assertEquals(committedMeetTime(), far.getMeetTime(), "中选行必须带定案会面时刻");
        // 2 km / 20 m/s = 100 000 ms → 同样延误 40 000 ms，靠 distKm 分出先后
        assertEquals("delay=40000.0;dist=2.00", far.getRankKey());
    }

    @Test
    @DisplayName("巡航未登记（NaN）→ 延误记「-」，不把 Infinity 落库")
    void record_shouldNotPersistInfinity() {
        CandidateGenerator.CandidateCombo unknownCruise = combo("C-9", "N-9", "D-9", 1.0, Double.NaN);
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                new CandidateFeasibilityPlanner.FeasibleSelection(unknownCruise,
                        List.of(verdict(unknownCruise, null, false)));

        service.record(order(), selection, committedMeetTime(), "PV-9", NOW_MS);

        assertEquals("delay=-;dist=1.00", written.get(0).getRankKey());
    }

    @Test
    @DisplayName("无死线时退回纯距离键：delay 恒 0（与 picker 旧口径一致）")
    void record_withoutDeadline_shouldFallbackToDistance() {
        OrderTaskEntity noDeadline = order();
        noDeadline.setExpectedTime(null);
        CandidateGenerator.CandidateCombo combo = combo("C-1", "N-1", "D-1", 1.0, 10.0);
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                new CandidateFeasibilityPlanner.FeasibleSelection(combo,
                        List.of(verdict(combo, null, false)));

        service.record(noDeadline, selection, committedMeetTime(), "PV-9", NOW_MS);

        assertEquals("delay=0.0;dist=1.00", written.get(0).getRankKey());
    }

    @Test
    @DisplayName("★ 落库失败 fail-soft：返回 0、不抛异常（留痕不阻断派单）")
    void record_whenInsertFails_shouldNotThrow() {
        insertFails = true;
        CandidateGenerator.CandidateCombo combo = combo("C-1", "N-1", "D-1", 1.0, 10.0);
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                new CandidateFeasibilityPlanner.FeasibleSelection(combo,
                        List.of(verdict(combo, null, false)));

        int rows = service.record(order(), selection, committedMeetTime(), "PV-9", NOW_MS);

        assertEquals(0, rows);
    }

    @Test
    @DisplayName("按订单回查：九项视图，会面时刻转 epoch 毫秒；空/未知订单回空列表")
    void listByOrder_shouldMapViews() {
        CandidateGenerator.CandidateCombo combo = combo("C-1", "N-1", "D-1", 1.5, 12.0);
        CandidateFeasibilityPlanner.FeasibleSelection selection =
                new CandidateFeasibilityPlanner.FeasibleSelection(combo,
                        List.of(verdict(combo, null, false)));
        service.record(order(), selection, committedMeetTime(), "PV-9", NOW_MS);

        List<CandidateSnapshotView> views = service.listByOrder("  " + ORDER_ID + " ");

        assertEquals(1, views.size());
        CandidateSnapshotView view = views.get(0);
        assertEquals(ORDER_ID, view.orderId());
        assertEquals("M30T", view.model());
        assertEquals("N-1", view.nestId());
        assertEquals("D-1", view.droneId());
        assertEquals(1.5, view.distKm());
        assertEquals(committedMeetTime().atZone(DB_ZONE).toInstant().toEpochMilli(),
                view.meetTimeMs());
        assertEquals(CandidateSnapshotService.CHOSEN_REASON, view.rejectReason());
        assertEquals("PV-9", view.snapshotVersion());

        assertTrue(service.listByOrder("  ").isEmpty());
        assertTrue(service.listByOrder("ORD-UNKNOWN").isEmpty());
    }

    // ───────────── 桩与夹具 ─────────────

    private static CandidateSnapshotEntity copy(CandidateSnapshotEntity src) {
        CandidateSnapshotEntity row = new CandidateSnapshotEntity();
        row.setOrderId(src.getOrderId());
        row.setModel(src.getModel());
        row.setNestId(src.getNestId());
        row.setDroneId(src.getDroneId());
        row.setDistKm(src.getDistKm());
        row.setMeetTime(src.getMeetTime());
        row.setRejectReason(src.getRejectReason());
        row.setRankKey(src.getRankKey());
        row.setSnapshotVersion(src.getSnapshotVersion());
        return row;
    }

    private CandidateSnapshotMapper inMemorySnapshotMapper() {
        return (CandidateSnapshotMapper) Proxy.newProxyInstance(
                CandidateSnapshotMapper.class.getClassLoader(),
                new Class<?>[]{CandidateSnapshotMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        if (insertFails) {
                            throw new IllegalStateException("模拟落库失败");
                        }
                        CandidateSnapshotEntity row = (CandidateSnapshotEntity) args[0];
                        written.add(copy(row));
                        return 1;
                    }
                    if ("selectByOrderId".equals(method.getName())) {
                        String orderId = (String) args[0];
                        List<CandidateSnapshotEntity> hit = new ArrayList<>();
                        for (CandidateSnapshotEntity row : written) {
                            if (row.getOrderId().equals(orderId)) {
                                hit.add(row);
                            }
                        }
                        return hit;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DroneMapper stubDroneMapper() {
        return (DroneMapper) Proxy.newProxyInstance(
                DroneMapper.class.getClassLoader(), new Class<?>[]{DroneMapper.class},
                (proxy, method, args) -> {
                    if ("selectBatchIds".equals(method.getName())) {
                        Collection<?> ids = (Collection<?>) args[0];
                        List<DroneEntity> drones = new ArrayList<>();
                        for (Object id : ids) {
                            DroneEntity drone = new DroneEntity();
                            drone.setDroneId((String) id);
                            // D-1 慢（10 m/s）、D-2 快（20 m/s）、D-9 未登记
                            if ("D-1".equals(id)) {
                                drone.setCruiseSpeedMs(new BigDecimal("10"));
                            } else if ("D-2".equals(id)) {
                                drone.setCruiseSpeedMs(new BigDecimal("20"));
                            }
                            drones.add(drone);
                        }
                        return drones;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SysParamLoader fixedSysParamLoader() {
        SysParamLoader loader = new SysParamLoader((SysParamMapper) Proxy.newProxyInstance(
                SysParamMapper.class.getClassLoader(), new Class<?>[]{SysParamMapper.class},
                (proxy, method, args) -> null)) {
            @Override
            public double getDouble(String key, double defaultValue) {
                return 1.0;
            }
        };
        return loader;
    }

    private static CandidateGenerator.CandidateCombo combo(String comboId, String nestId,
            String droneId, double distKm, double cruiseSpeedMs) {
        return new CandidateGenerator.CandidateCombo(comboId, "M30T", nestId, droneId,
                distKm, 90.0, 12.0, cruiseSpeedMs);
    }

    private static CandidateFeasibilityPlanner.CandidateVerdict verdict(
            CandidateGenerator.CandidateCombo combo, String reason, boolean infeasible) {
        return new CandidateFeasibilityPlanner.CandidateVerdict(
                combo.comboId(), combo.type(), combo.nestId(), combo.droneId(), combo.distKm(),
                false,
                infeasible ? FeasibilityJudge.Verdict.INFEASIBLE : FeasibilityJudge.Verdict.FEASIBLE,
                reason == null ? List.of() : List.of(reason));
    }

    private static OrderTaskEntity order() {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(ORDER_ID);
        // 死线 = now + 60 s，便于手算延误（见用例注释）
        order.setExpectedTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(NOW_MS + 60_000L), DB_ZONE));
        return order;
    }

    private static LocalDateTime committedMeetTime() {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(NOW_MS + 90_000L), DB_ZONE);
    }
}
