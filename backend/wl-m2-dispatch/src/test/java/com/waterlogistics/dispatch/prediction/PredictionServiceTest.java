package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionService} 单测（M2 · 卢肄文）。
 *
 * <p>覆盖当日验收三条：① 收到船位能刷出预测轨迹；② <b>椭圆半轴是标准差不是方差</b>；
 * ③ 快照可供下游（M3 端口）取用。另验证版本号单调、帧为快照子集、单位换算正确。
 *
 * <p>不依赖 Spring 容器与 MQ：直接 new 出被测对象，保证算法链路可脱离全项目单测。
 */
class PredictionServiceTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final double LON = 121.4700;
    private static final double LAT = 30.5000;
    private static final double METERS_PER_DEG_LAT = com.waterlogistics.algo.meeting.GeoApprox.METERS_PER_DEG;

    private PredictionService service;

    private LoggingRecomputeRequester recomputeRequester;

    @BeforeEach
    void setUp() {
        // 与 PredictionConfig 从 sys_param 取值的形状同构，但显式传入以便断言确定值
        PredictionSettings settings = new PredictionSettings(
                30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        recomputeRequester = new LoggingRecomputeRequester();
        service = new PredictionService(settings, recomputeRequester);
    }

    private static AisMessage ais(String mmsi, double sogKnots, double cogDeg, long ts) {
        return new AisMessage(mmsi, "测试船", LON, LAT, sogKnots, cogDeg, ts);
    }

    @Test
    @DisplayName("验收①：收到船位即刷出预测轨迹（点数由参数决定）")
    void refresh_shouldProduceTrack() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 10.0, 0.0, T0));

        assertEquals("413000001", snapshot.mmsi());
        assertEquals(T0, snapshot.t0Ms());
        assertEquals(11, snapshot.points().size(), "600 s / 60 s + 1 = 11 点");
        assertEquals(1, snapshot.version(), "首次刷新版本号为 1");
        assertEquals("VALID", snapshot.premise());
        assertEquals(900L, snapshot.validitySec());
        assertEquals(1, service.trackedShipCount());
    }

    @Test
    @DisplayName("版本号每船单调递增（契约：判新旧只看 version）")
    void version_shouldIncreasePerShip() {
        long first = service.refresh(ais("413000001", 10.0, 0.0, T0)).version();
        long second = service.refresh(ais("413000001", 10.0, 0.0, T0 + 1000L)).version();
        long otherShip = service.refresh(ais("413000002", 10.0, 0.0, T0)).version();

        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(1, otherShip, "版本号按船独立计数");
        assertEquals(2, service.trackedShipCount());
    }

    @Test
    @DisplayName("验收②：椭圆半轴 = 2σ（标准差，不是方差）")
    void framePoint_shouldExposeStdDevNotVariance() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 10.0, 45.0, T0));
        ShipTrajPoint last = snapshot.points().get(snapshot.points().size() - 1);

        // 期望：σ∥ = 30 + 0.15×600 = 120 m；σ⊥ = 10 + 0.02×600 = 22 m
        assertEquals(120.0, last.sigmaAlong(), 1e-9);
        assertEquals(22.0, last.sigmaCross(), 1e-9);
        assertEquals(240.0, last.semiMajorM(), 1e-9, "半长轴 = 2σ∥");
        assertEquals(44.0, last.semiMinorM(), 1e-9, "半短轴 = 2σ⊥");
    }

    @Test
    @DisplayName("单位换算：sog 是节，须先换成 m/s 再外推")
    void sog_shouldBeConvertedFromKnots() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 10.0, 0.0, T0));
        ShipTrajPoint last = snapshot.points().get(snapshot.points().size() - 1);

        double movedNorthM = (last.lat() - LAT) * METERS_PER_DEG_LAT;
        // 10 节 = 5.14444 m/s，600 s → 3086.67 m
        assertEquals(3086.67, movedNorthM, 1.0, "若把节当 m/s 用，此值会偏大约一倍");
    }

    @Test
    @DisplayName("验收③：快照可被下游取用；帧是快照的子集")
    void snapshot_shouldBeRetrievableAndFrameIsSubset() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 8.0, 90.0, T0));

        assertTrue(service.snapshotOf("413000001").isPresent());
        assertTrue(service.snapshotOf("999999999").isEmpty(), "未跟踪的船应取不到");

        ShipTrajFrame frame = snapshot.toFrame();
        assertEquals(snapshot.version(), frame.version());
        assertEquals(snapshot.t0Ms(), frame.t0());
        assertEquals(snapshot.courseDeg(), frame.courseDeg());
        assertEquals(snapshot.validitySec(), frame.validitySec());
        assertEquals(snapshot.premise(), frame.premise());
        assertEquals(snapshot.points(), frame.points());

        // 帧缺的五项在快照里占位存在（精算版填充），今天为空 —— 证明快照是超集
        assertNull(snapshot.zSeries());
        assertNull(snapshot.deviationM());
        assertNull(snapshot.enterTimesMs());
        assertNull(snapshot.branchId());
        assertNull(snapshot.calibrationFactor());
    }

    @Test
    @DisplayName("有效期判定：超过 validitySec 即过期")
    void isStale_shouldFollowValidity() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 10.0, 0.0, T0));

        assertFalse(snapshot.isStale(T0));
        assertFalse(snapshot.isStale(T0 + 900_000L));
        assertTrue(snapshot.isStale(T0 + 900_001L));
    }

    @Test
    @DisplayName("多船快照按 MMSI 稳定排序")
    void latestSnapshots_shouldBeSortedByMmsi() {
        service.refresh(ais("413000003", 10.0, 0.0, T0));
        service.refresh(ais("413000001", 10.0, 0.0, T0));
        service.refresh(ais("413000002", 10.0, 0.0, T0));

        List<String> mmsis = service.latestSnapshots().stream().map(PredictionSnapshot::mmsi).toList();
        assertEquals(List.of("413000001", "413000002", "413000003"), mmsis);
    }

    @Test
    @DisplayName("越界观测不写入快照，异常向上抛给调用方")
    void refresh_shouldRejectOutOfRangeObservation() {
        AisMessage bad = new AisMessage("413000001", "测试船", LON, 100.0, 10.0, 0.0, T0);

        assertThrows(IllegalArgumentException.class, () -> service.refresh(bad));
        assertEquals(0, service.trackedShipCount(), "脏数据不得建出快照");
    }

    // ───────────────────────── 预测刷新驱动链（10-16 任务） ─────────────────────────

    @Test
    @DisplayName("★ 驱动链：航向跳变超阈 → 发出重算请求（解锁 6a）")
    void courseJump_shouldRequestRecompute() {
        service.refresh(ais("413000001", 8.0, 0.0, T0));
        assertEquals(0, recomputeRequester.requestCount(), "首帧没有可比上一版，不算跳变");

        service.refresh(ais("413000001", 8.0, 90.0, T0 + 1000L));
        assertEquals(1, recomputeRequester.requestCount(), "90° 跳变应触发重算请求");

        RecomputeRequest request = recomputeRequester.recentRequests().get(0);
        assertEquals("413000001", request.mmsi());
        assertEquals(2L, request.snapshotVersion(), "带上触发时的快照版本，供 M3 做一致性锚");
        assertEquals(0.0, request.previousCourseDeg(), 1e-9);
        assertEquals(90.0, request.currentCourseDeg(), 1e-9);
        assertEquals(90.0, request.courseDeltaDeg(), 1e-9);
        assertEquals(PredictionService.REASON_COURSE_JUMP, request.triggerReason());
    }

    @Test
    @DisplayName("小幅航向变化不触发重算（避免噪声刷请求）")
    void smallCourseChange_shouldNotRequestRecompute() {
        service.refresh(ais("413000001", 8.0, 0.0, T0));
        service.refresh(ais("413000001", 8.0, 10.0, T0 + 1000L));

        assertEquals(0, recomputeRequester.requestCount(),
                "10° 小于阈值 " + PredictionService.RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG + "°，不应触发");
    }

    @Test
    @DisplayName("★ 航向跨 360° 要取最短弧：350° → 10° 只算 20°")
    void shortestArc_shouldHandleWrapAround() {
        assertEquals(20.0, PredictionService.shortestArcDeg(350.0, 10.0), 1e-9);
        assertEquals(20.0, PredictionService.shortestArcDeg(10.0, 350.0), 1e-9);
        assertEquals(180.0, PredictionService.shortestArcDeg(0.0, 180.0), 1e-9);
        assertEquals(0.0, PredictionService.shortestArcDeg(90.0, 90.0), 1e-9);
    }

    @Test
    @DisplayName("跨 360° 的跳变按最短弧判：15° 不触发，25° 触发")
    void wrapAroundJump_shouldFollowThreshold() {
        service.refresh(ais("413000001", 8.0, 350.0, T0));

        service.refresh(ais("413000001", 8.0, 5.0, T0 + 1000L));
        assertEquals(0, recomputeRequester.requestCount(), "350°→5° 实为 15°，未达阈值");

        // 与「上一版 5°」比（不是与第一版 350° 累计比）：比较基线是相邻两版，否则会反复触发
        service.refresh(ais("413000001", 8.0, 30.0, T0 + 2000L));
        assertEquals(1, recomputeRequester.requestCount(), "5°→30° 为 25°，越过阈值 20°");
    }

    @Test
    @DisplayName("★ 给 E 组 6b 的配对样本：预测切向来自快照，不经 WS 帧反推")
    void toHeadingSample_shouldPairMeasuredCogWithPredictedTangent() {
        AisMessage measured = ais("413000001", 10.0, 45.0, T0);
        service.refresh(measured);

        HeadingSample sample = service.toHeadingSample(measured).orElseThrow();

        assertEquals(45.0, sample.cogDeg(), 1e-9, "实测 COG 原样带入");
        assertEquals(45.0, sample.predictedTangentDeg(), 0.5,
                "简化版匀速直线航迹：切向应约等于主轴方位");
        assertEquals(10.0 * (1852.0 / 3600.0), sample.sogMs(), 1e-9, "节 → m/s");
        assertEquals(T0, sample.tsMs());
    }

    @Test
    @DisplayName("未知船取配对样本应为空（调用方跳过本拍）")
    void toHeadingSample_shouldBeEmptyForUnknownShip() {
        assertTrue(service.toHeadingSample(ais("999999999", 10.0, 0.0, T0)).isEmpty());
    }

    // =================================================================
    // 11-02 评审：HIGH.24 / MEDIUM.46 / MEDIUM.4 / MEDIUM.47 / MEDIUM.49 / LOW.20
    // =================================================================

    @Test
    @DisplayName("★ HIGH.24：ship_traj 帧带 mmsi，大屏才能把一帧归到某条船")
    void toFrame_shouldCarryMmsi() {
        PredictionSnapshot snapshot = service.refresh(ais("413000777", 10.0, 0.0, T0));

        ShipTrajFrame frame = snapshot.toFrame();

        assertEquals("413000777", frame.mmsi(), "帧里没有船标识，多船同时跟时就无法归属");
        assertEquals(snapshot.version(), frame.version());
        assertEquals(snapshot.points(), frame.points());
    }

    @Test
    @DisplayName("★ MEDIUM.46：观测越界抛异常时版本号必须退回，不留空洞")
    void rejectedObservation_shouldNotConsumeVersion() {
        // 纬度 200 越界：ConstantVelocityPredictor 的 validate 会抛 IllegalArgumentException
        AisMessage bad = new AisMessage("413000001", "测试船", LON, 200.0, 10.0, 0.0, T0);

        assertThrows(IllegalArgumentException.class, () -> service.refresh(bad));
        assertTrue(service.snapshotOf("413000001").isEmpty(), "越界观测不写快照");

        PredictionSnapshot good = service.refresh(ais("413000001", 10.0, 0.0, T0));
        assertEquals(1, good.version(),
                "版本序列不该因为一次失败而出现空洞（method javadoc 承诺「抛出时不递增版本」）");
    }

    @Test
    @DisplayName("★ MEDIUM.4：evictStale 同步清掉四个 Map，只留仍在有效期内的船")
    void evictStale_shouldClearAllPerShipMaps() {
        long now = T0 + 10_000L;
        PredictionSettings settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService clocked = new PredictionService(settings,
                new LoggingRecomputeRequester(), event -> { }, PredictionProfile.constant(),
                () -> now);

        clocked.refresh(ais("413000001", 10.0, 90.0, T0));
        clocked.refresh(ais("413000002", 10.0, 90.0, T0));
        assertEquals(2, clocked.trackedShipCount());

        // 有效期 900 s：把「现在」推到 T0+901 s，两船都过期
        int evicted = clocked.evictStale(T0 + 901_000L);

        assertEquals(2, evicted);
        assertEquals(0, clocked.trackedShipCount());
        assertTrue(clocked.snapshotOf("413000001").isEmpty());
        assertEquals(0, clocked.latestSnapshots().size());
        // 观测与航向也要干净：留着它们，重算会拿旧观测算出一版"凭空复活"的快照
        assertTrue(clocked.recompute("413000001").isEmpty(),
                "观测已清 ⇒ 重算必须返回空，否则被清掉的船会凭空复活");
    }

    @Test
    @DisplayName("evictStale 不清仍新鲜、也未超 TTL 的船")
    void evictStale_shouldKeepFreshShips() {
        long now = T0 + 10_000L;
        PredictionSettings settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService clocked = new PredictionService(settings,
                new LoggingRecomputeRequester(), event -> { }, PredictionProfile.constant(),
                () -> now);

        clocked.refresh(ais("413000001", 10.0, 90.0, T0));

        assertEquals(0, clocked.evictStale(T0 + 60_000L), "60 s 后仍新鲜");
        assertEquals(1, clocked.trackedShipCount());
    }

    @Test
    @DisplayName("★ MEDIUM.47：点列不足以定出切向时返回空，不拿主轴方位顶替")
    void tangentDegAt_shouldBeEmptyWhenPointsInsufficient() {
        // 精算快照的 courseDeg 是「参考线走向」而不是船的航迹切向；
        // 点列 < 2 时若拿它当 predictedTangentDeg，E 组 6b 的成向判定会带系统性偏差。
        PredictionSnapshot singlePoint = new PredictionSnapshot("413000001", 1L, T0, 90.0, 900L,
                "VALID", List.of(new ShipTrajPoint(T0, LON, LAT, 20.0, 15.0)),
                null, null, null, null, null);

        assertTrue(PredictionService.tangentDegAt(singlePoint, T0).isEmpty());
        assertTrue(PredictionService.tangentDegAt(
                new PredictionSnapshot("413000001", 1L, T0, 90.0, 900L, "VALID",
                        List.of(), null, null, null, null, null), T0).isEmpty(),
                "空点列同样取不到切向");
    }

    @Test
    @DisplayName("切向取得到时仍是相邻点方位（改动没有把正常路径切断）")
    void tangentDegAt_shouldStillWorkForNormalTrack() {
        PredictionSnapshot snapshot = service.refresh(ais("413000001", 10.0, 90.0, T0));

        double tangent = PredictionService.tangentDegAt(snapshot, T0).orElseThrow();
        assertEquals(90.0, tangent, 0.5, "正东航迹的切向约 90°");
    }

    @Test
    @DisplayName("★ MEDIUM.49：zSeries / enterTimesMs 深拷贝，调用方改原列表不影响已发布快照")
    void snapshot_shouldDefensivelyCopyNullableLists() {
        java.util.List<Double> mutableZ = new java.util.ArrayList<>(List.of(1.0, 2.0));
        java.util.List<Long> mutableEnter = new java.util.ArrayList<>(List.of(T0));

        PredictionSnapshot snapshot = new PredictionSnapshot("413000001", 1L, T0, 90.0, 900L,
                "VALID", List.of(new ShipTrajPoint(T0, LON, LAT, 20.0, 15.0)),
                mutableZ, null, mutableEnter, null, null);

        mutableZ.add(99.0);
        mutableEnter.add(T0 + 1L);

        assertEquals(2, snapshot.zSeries().size(), "新息序列不该被生产方事后改写");
        assertEquals(1, snapshot.enterTimesMs().size());
    }

    @Test
    @DisplayName("★ LOW.20：t0 超前（时钟回拨）不判过期；有效期溢出也不翻成「未过期」")
    void isStale_shouldHandleFutureT0AndOverflow() {
        PredictionSnapshot future = new PredictionSnapshot("413000001", 1L, T0, 90.0, 900L,
                "VALID", List.of(new ShipTrajPoint(T0, LON, LAT, 20.0, 15.0)),
                null, null, null, null, null);

        assertFalse(future.isStale(T0 - 3_600_000L), "t0 超前时 elapsed 为负，不能判过期");

        PredictionSnapshot huge = new PredictionSnapshot("413000001", 1L, T0, 90.0,
                Long.MAX_VALUE, "VALID",
                List.of(new ShipTrajPoint(T0, LON, LAT, 20.0, 15.0)),
                null, null, null, null, null);
        assertFalse(huge.isStale(T0 + 1_000L), "有效期极大时不该因乘法溢出而误判过期");
    }

    @Test
    @DisplayName("空点列不发帧：publish 跳过，不抛给调用方")
    void emptySnapshot_shouldNotPublishFrame() {
        java.util.List<ShipTrajFrame> published = new java.util.ArrayList<>();
        PredictionSettings settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService publisher = new PredictionService(settings,
                new LoggingRecomputeRequester(),
                event -> published.add(((PredictionUpdatedEvent) event).frame()),
                PredictionProfile.constant(), () -> T0 + 1000L);

        publisher.refresh(ais("413000001", 10.0, 0.0, T0));

        assertEquals(1, published.size(), "正常快照应发一帧");
        assertEquals("413000001", published.get(0).mmsi());
    }
}
