package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    private PredictionService service;

    @BeforeEach
    void setUp() {
        // 与 PredictionSettings 的 @Value 缺省同构，但显式传入以便断言确定值
        PredictionSettings settings = new PredictionSettings(
                30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        service = new PredictionService(settings);
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
}
