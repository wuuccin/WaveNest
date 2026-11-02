package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TrajectorySnapshotAdapter} 单测（M2 · 卢肄文）——<b>端口投影口径的守门测试</b>。
 *
 * <p>覆盖 M3 端口的契约要点：查无快照<b>返回 null</b>（由 M3 侧判 null 后抛
 * {@code SnapshotUnavailableException}）、版本号以十进制字符串投影、
 * 逐点 {@code cog} 由相邻预测点补齐、σ 语义为<b>标准差</b>原样搬运。
 *
 * <p>不依赖 Spring 与数据库：直接构造适配器。
 */
class TrajectorySnapshotAdapterTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final double LON = 121.4700;
    private static final double LAT = 30.5000;

    private PredictionService service;
    private TrajectorySnapshotAdapter adapter;

    @BeforeEach
    void setUp() {
        PredictionSettings settings = new PredictionSettings(
                30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        service = new PredictionService(settings, new LoggingRecomputeRequester());
        adapter = new TrajectorySnapshotAdapter(service);
    }

    private static AisMessage ais(String mmsi, double cogDeg) {
        return new AisMessage(mmsi, "测试船", LON, LAT, 10.0, cogDeg, T0);
    }

    @Test
    @DisplayName("端口投影：version 转字符串、validitySec 转 int、点列与 σ 原样搬运")
    void latest_shouldProjectToShipTrackSnapshot() {
        PredictionSnapshot internal = service.refresh(ais("413000001", 45.0));

        ShipTrackSnapshot projected = adapter.latest("413000001");

        assertNotNull(projected);
        assertEquals("1", projected.version(), "内部 long 版本 → M3 侧十进制字符串");
        assertEquals("413000001", projected.shipMmsi());
        assertEquals(T0, projected.t0());
        assertEquals(900, projected.validitySec());
        assertEquals(internal.points().size(), projected.points().size());

        ShipPoint first = projected.points().get(0);
        assertEquals(internal.points().get(0).t(), first.t());
        assertEquals(internal.points().get(0).lon(), first.lon(), 1e-12);
        assertEquals(internal.points().get(0).lat(), first.lat(), 1e-12);
        assertEquals(internal.points().get(0).sigmaAlong(), first.sigmaAlong(), 1e-12,
                "σ 是标准差，原样搬运、不做平方");
        assertEquals(internal.points().get(0).sigmaCross(), first.sigmaCross(), 1e-12);
    }

    @Test
    @DisplayName("逐点 cog 由相邻预测点求方位补齐；末点沿用前一段")
    void pointsCog_shouldFollowPredictedBearing() {
        service.refresh(ais("413000001", 90.0));

        List<ShipPoint> points = adapter.latest("413000001").points();

        for (ShipPoint point : points) {
            assertEquals(90.0, point.cog(), 0.5, "正东航迹的每段方位都应是 90°");
        }
        assertEquals(points.get(points.size() - 2).cog(), points.get(points.size() - 1).cog(), 1e-9,
                "末点没有下一点，应沿用前一段方位");
    }

    @Test
    @DisplayName("★ 查无快照返回 null（M3 侧据此抛 SnapshotUnavailableException）")
    void latest_shouldReturnNullWhenUnknown() {
        assertNull(adapter.latest("999999999"));
        assertThrows(IllegalArgumentException.class, () -> adapter.latest(null));
        assertThrows(IllegalArgumentException.class, () -> adapter.latest("   "));
    }

    @Test
    @DisplayName("刷新后再取可拿到新版本（判新旧只看 version）")
    void latest_shouldFollowVersionIncrease() {
        service.refresh(ais("413000001", 0.0));
        service.refresh(ais("413000001", 0.0));

        assertEquals("2", adapter.latest("413000001").version());
        assertTrue(service.snapshotOf("413000001").isPresent());
    }

    // =================================================================
    // 11-02 评审 HIGH.25 / HIGH.26 / MEDIUM.50 的守门用例
    // =================================================================

    private static PredictionSnapshot snapshotOf(long validitySec, List<ShipTrajPoint> points) {
        return new PredictionSnapshot("413000001", 7L, T0, 90.0, validitySec, "VALID",
                points, null, null, null, null, null);
    }

    private static ShipTrajPoint pointAt(long tMs, double lon, double lat) {
        return new ShipTrajPoint(tMs, lon, lat, 20.0, 15.0);
    }

    @Test
    @DisplayName("★ HIGH.25：有效期超出 int 范围／非正时明确失败，而不是静默回绕成负数")
    void validitySec_whenNotProjectable_shouldFailLoudly() {
        // 2147483648 强转 int 会变成 -2147483648，ShipTrackSnapshot 随即抛
        // IllegalArgumentException——而端口契约里那个异常的含义是「调用方传错了船号」。
        // 这里要钉的是：失败必须是 IllegalStateException，且消息里带得出船号与版本。
        IllegalStateException overflow = assertThrows(IllegalStateException.class,
                () -> TrajectorySnapshotAdapter.toIntValiditySec(
                        snapshotOf(2_147_483_648L, List.of(pointAt(T0, LON, LAT)))));
        assertTrue(overflow.getMessage().contains("413000001"), "消息须带船号，否则排障要翻日志：" + overflow.getMessage());

        assertThrows(IllegalStateException.class, () -> TrajectorySnapshotAdapter.toIntValiditySec(
                snapshotOf(0L, List.of(pointAt(T0, LON, LAT)))), "有效期为 0 同样不可投影");

        assertEquals(900, TrajectorySnapshotAdapter.toIntValiditySec(
                snapshotOf(900L, List.of(pointAt(T0, LON, LAT)))), "正常值原样搬运");
    }

    @Test
    @DisplayName("★ HIGH.26：点列为空按『无可用预测』返回 null，不产空帧、不抛参数异常")
    void emptyPoints_shouldMapToNullInsteadOfEmptyFrame() {
        PredictionSnapshot empty = snapshotOf(900L, List.of());

        assertNull(TrajectorySnapshotAdapter.toShipTrackSnapshotOrNull(empty),
                "空点列是合法状态（这版没有可用轨迹），应走『无预测』分支让 M3 拒绝求解");

        // 严格版仍要响亮：调用方已假定点列非空时，空点列就是实现缺陷
        IllegalStateException strict = assertThrows(IllegalStateException.class,
                () -> TrajectorySnapshotAdapter.toShipTrackSnapshot(empty));
        assertTrue(strict.getMessage().contains("413000001"), strict.getMessage());
    }

    @Test
    @DisplayName("★ MEDIUM.50：相邻两点重合时不返回正北 0°，回落快照主轴方位")
    void coincidentPoints_shouldFallBackToMainAxis() {
        // 静止船：所有预测点在同一坐标。bearingDeg 在 atan2(0,0) 处给 0（正北），
        // 那是个"看起来完全合法"的航向，M3 会据此把停着的船当成向北行驶。
        double lon = 115.8611;
        double lat = 28.6660;
        PredictionSnapshot stationary = snapshotOf(900L, List.of(
                pointAt(T0, lon, lat),
                pointAt(T0 + 60_000L, lon, lat),
                pointAt(T0 + 120_000L, lon, lat)));

        ShipTrackSnapshot projected = TrajectorySnapshotAdapter.toShipTrackSnapshot(stationary);

        for (ShipPoint point : projected.points()) {
            assertEquals(90.0, point.cog(), 1e-9,
                    "重合段应回落主轴方位 90°，而不是正北 0°");
        }
    }

    @Test
    @DisplayName("亚毫米级抖动也算重合（严格 == 会漏判 1e-12 级差异）")
    void nearCoincidentPoints_shouldAlsoFallBack() {
        double lon = 115.8611;
        double lat = 28.6660;
        PredictionSnapshot jittered = snapshotOf(900L, List.of(
                pointAt(T0, lon, lat),
                pointAt(T0 + 60_000L, lon + 1e-12, lat - 1e-12)));

        assertEquals(90.0,
                TrajectorySnapshotAdapter.toShipTrackSnapshot(jittered).points().get(0).cog(), 1e-9,
                "浮点抖动下的 atan2(tiny, tiny) 方向由噪声决定，比齐刷刷指正北更难察觉");
    }
}
