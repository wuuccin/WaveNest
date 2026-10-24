package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F3「重算链接通」单测（M2 · 卢肄文 · 10-23 任务清单 §三）。
 *
 * <p>判据原文：「触发重算后预测快照确实更新，并产生更新事件」。本类把这条判据拆成可断言的三件事：
 * <ol>
 *   <li>航向跳变 → 重算请求产生（入端，原有用例已覆盖，这里只取 1 次作前置）；</li>
 *   <li>请求 → {@link PredictionService#recompute(String)} 真被调用：版本号 +1；</li>
 *   <li>重算 → {@link PredictionUpdatedEvent} 真被发出，且事件顺序不倒退。</li>
 * </ol>
 *
 * <p>另覆盖两条容易出事的地方：② 重算不自激（不得把同一件事再判一遍形成活锁）；
 * ③ 落点未装配／重算抛异常时，信号热路径不得把异常抛回给预测刷新。
 */
class RecomputeClosedLoopTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final double LON = 121.4700;
    private static final double LAT = 30.5000;

    private PredictionSettings settings;

    private LoggingRecomputeRequester requester;

    private List<PredictionUpdatedEvent> events;

    private PredictionService service;

    @BeforeEach
    void setUp() {
        settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        requester = new LoggingRecomputeRequester();
        events = new ArrayList<>();
        service = new PredictionService(settings, requester, event -> {
            if (event instanceof PredictionUpdatedEvent updated) {
                events.add(updated);
            }
        });
        // 生产线由 Spring 用 @Lazy setter 注入；单测里直接给定同一实例，等价
        requester.bindPredictionService(service);
    }

    private static AisMessage ais(String mmsi, double sogKnots, double cogDeg, long ts) {
        return new AisMessage(mmsi, "测试船", LON, LAT, sogKnots, cogDeg, ts);
    }

    @Test
    @DisplayName("★ F3 判据：航向跳变 → 发请求 → 快照重算（版本 +1）→ 产生更新事件")
    void courseJump_shouldRecomputeSnapshotAndPublishEvent() {
        service.refresh(ais("413000001", 8.0, 0.0, T0));
        assertEquals(1, service.snapshotOf("413000001").orElseThrow().version());
        assertEquals(1, events.size());

        // 90° 跳变：本版 v2 → 发信号 → 回跳重算出 v3
        service.refresh(ais("413000001", 8.0, 90.0, T0 + 1000L));

        assertEquals(1, requester.requestCount(), "跳变应产生 1 次重算请求");
        assertEquals(3, service.snapshotOf("413000001").orElseThrow().version(),
                "重算必须真落一版：v2（刷新）+ 1 = v3");
        assertEquals(3, events.size(), "刷新 v1、v2 与重算 v3 各广播一次");
        assertEquals(90.0, service.snapshotOf("413000001").orElseThrow().courseDeg(), 1e-9,
                "重算用的是跳变后的最新观测");
    }

    @Test
    @DisplayName("事件版本严格递增：重算不得让下游先收到旧版")
    void publishedVersions_shouldBeMonotonic() {
        service.refresh(ais("413000001", 8.0, 0.0, T0));
        service.refresh(ais("413000001", 8.0, 90.0, T0 + 1000L));

        List<Long> versions = events.stream().map(event -> event.frame().version()).toList();
        assertEquals(List.of(1L, 2L, 3L), versions);
    }

    @Test
    @DisplayName("★ 重算不自激：同一观测重算后不得再发一次请求")
    void recompute_shouldNotEmitAnotherRequest() {
        service.refresh(ais("413000001", 8.0, 0.0, T0));
        service.refresh(ais("413000001", 8.0, 90.0, T0 + 1000L));

        assertEquals(1, requester.requestCount(),
                "重算走的是不回发信号的路径；若回发，这里会变成 2 并可能继续增长");
    }

    @Test
    @DisplayName("重算口径：同一观测重建，点位不变、只递进版本")
    void recompute_shouldRebuildFromLatestObservation() {
        service.refresh(ais("413000001", 8.0, 45.0, T0));
        service.refresh(ais("413000001", 8.0, 135.0, T0 + 1000L));

        PredictionSnapshot afterRecompute = service.snapshotOf("413000001").orElseThrow();
        assertEquals(T0 + 1000L, afterRecompute.t0Ms(), "重算用的时标取最新观测，不取首帧");
        assertEquals(135.0, afterRecompute.courseDeg(), 1e-9);

        ShipTrajPoint last = afterRecompute.points().get(afterRecompute.points().size() - 1);
        // 135° 航向、8 节、外推 600 s：北路分量为负（向南）
        double expectedNorthM = 8.0 * (1852.0 / 3600.0) * 600.0 * Math.cos(Math.toRadians(135.0));
        double movedM = (last.lat() - LAT) * com.waterlogistics.algo.meeting.GeoApprox.METERS_PER_DEG;
        assertEquals(expectedNorthM, movedM, 1.0);
    }

    @Test
    @DisplayName("无观测的船重算返回空，且不虚增版本")
    void recompute_shouldBeEmptyForUnknownShip() {
        assertTrue(service.recompute("999999999").isEmpty());
        assertTrue(service.snapshotOf("999999999").isEmpty());
        assertEquals(0, service.trackedShipCount());
    }

    @Test
    @DisplayName("落点未装配（无参构造）时只留痕，不抛异常")
    void requester_withoutDriver_shouldOnlyRecord() {
        LoggingRecomputeRequester standalone = new LoggingRecomputeRequester();

        assertDoesNotThrow(() -> standalone.requestRecompute(new RecomputeRequest(
                "413000001", 2L, 0.0, 90.0, 90.0, PredictionService.REASON_COURSE_JUMP, T0)));

        assertEquals(1, standalone.requestCount());
    }

    @Test
    @DisplayName("重算抛异常不得回抛给预测刷新热路径")
    void requester_shouldSwallowRecomputeFailure() {
        PredictionService failing = new PredictionService(settings, requester) {
            @Override
            public java.util.Optional<PredictionSnapshot> recompute(String mmsi) {
                throw new IllegalStateException("算法层故障");
            }
        };
        requester.bindPredictionService(failing);

        assertDoesNotThrow(() -> failing.refresh(ais("413000001", 8.0, 0.0, T0)));
        assertDoesNotThrow(() -> failing.refresh(ais("413000001", 8.0, 90.0, T0 + 1000L)));

        assertEquals(1, requester.requestCount(), "请求照常留痕");
        assertEquals(2, failing.snapshotOf("413000001").orElseThrow().version(),
                "重算失败不影响刷新本身落版");
    }
}
