package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.contract.mq.AisMessage;
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
}
