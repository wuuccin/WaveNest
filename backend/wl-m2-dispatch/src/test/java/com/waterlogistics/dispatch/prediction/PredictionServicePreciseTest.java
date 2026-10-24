package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预测切精算的服务层验收（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>覆盖清单「判据」里的服务层三项：
 * <ol>
 *   <li>同一组输入下，精算版与匀速版点位差异在合理范围；</li>
 *   <li>精算版的 σ 与新息序列确实落到快照上（不是又走回匀速版）；</li>
 *   <li>回退开关有效：{@code constant} 模式行为与切精算之前完全一致。</li>
 * </ol>
 *
 * <p>另验一条「网」的兜底：精算输出落在赣江框外时<b>本帧</b>回落匀速版并计数，
 * 不会把没验过的数发出去。
 */
class PredictionServicePreciseTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String MMSI = "413000002";
    private static final double ORIGIN_LON = 115.9000;
    private static final double ORIGIN_LAT = 28.6500;
    private static final double MPD_LAT = WaterwayGeometry.METERS_PER_DEG_LAT;
    private static final double KNOT_TO_MS = 1852.0 / 3600.0;
    private static final double BEARING_DEG = 30.0;
    private static final double SPEED_MS = 3.0;

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    private final PredictionAssurance assurance =
            new PredictionAssurance(PredictionAssurance.Bounds.demoArea());

    private static AisMessage observation(String mmsi, double lon0, double lat0, double elapsedSec) {
        double east = SPEED_MS * elapsedSec * Math.sin(Math.toRadians(BEARING_DEG));
        double north = SPEED_MS * elapsedSec * Math.cos(Math.toRadians(BEARING_DEG));
        double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(lat0));
        return new AisMessage(mmsi, "测船",
                lon0 + east / metersPerDegLon,
                lat0 + north / MPD_LAT,
                SPEED_MS / KNOT_TO_MS,
                BEARING_DEG,
                T0 + Math.round(elapsedSec * 1000.0));
    }

    private PredictionService preciseService() {
        PredictionProfile profile = PredictionProfile.precise(settings, assurance,
                PrecisePredictionEngine.Params.defaults());
        return new PredictionService(settings, new LoggingRecomputeRequester(), event -> { },
                profile, () -> T0 + 3_600_000L);
    }

    private PredictionService constantService() {
        return new PredictionService(settings, new LoggingRecomputeRequester(), event -> { },
                PredictionProfile.constant(), () -> T0 + 3_600_000L);
    }

    @Test
    @DisplayName("★ 精算版：σ 与新息序列落到快照，有效期来自带宽而非配置常量")
    void preciseMode_shouldFillPreciseFields() {
        PredictionService service = preciseService();
        assertEquals(PredictionMode.PRECISE, service.mode());

        // 首帧冷启动建不出参考线，回落匀速版（不报错、不缺帧）
        PredictionSnapshot first = service.refresh(observation(MMSI, ORIGIN_LON, ORIGIN_LAT, 0.0));
        assertNull(first.zSeries(), "冷启动帧走匀速版回退");

        PredictionSnapshot snapshot = service.refresh(observation(MMSI, ORIGIN_LON, ORIGIN_LAT, 10.0));

        assertEquals(2L, snapshot.version());
        assertEquals(31, snapshot.points().size(), "1800 s / 60 s + 1");
        assertEquals("VALID", snapshot.premise());
        assertNotNull(snapshot.zSeries(), "精算版必须带新息序列");
        assertTrue(snapshot.zSeries().size() <= PrecisePredictionEngine.Z_SERIES_CAP);
        assertTrue(snapshot.validitySec() > 0L);
        // 匀速版占位字段仍为空（偏差校准需 A-11 的样本表，尚未建）
        assertNull(snapshot.deviationM());
        assertNull(snapshot.calibrationFactor());
        assertEquals(0L, service.preciseRejectionCount(), "合规输出不应被断言网拦下");
    }

    @Test
    @DisplayName("★ 回退开关：constant 模式与切精算之前的输出口径一致")
    void constantMode_shouldKeepLegacyOutput() {
        PredictionService service = constantService();
        assertEquals(PredictionMode.CONSTANT, service.mode());

        PredictionSnapshot snapshot = service.refresh(observation(MMSI, ORIGIN_LON, ORIGIN_LAT, 0.0));

        assertNull(snapshot.zSeries());
        assertEquals(900L, snapshot.validitySec(), "匀速版有效期仍取配置常量");
        ShipTrajPoint last = snapshot.points().get(snapshot.points().size() - 1);
        double expectedSigmaAlong = 20.0 + 0.05 * 1800.0;
        assertEquals(expectedSigmaAlong, last.sigmaAlong(), 1e-9, "固定噪声带口径不得变");
    }

    @Test
    @DisplayName("★ 判据 1：同输入下精算与匀速点位差在合理范围")
    void preciseAndConstant_shouldBeComparable() {
        PredictionService precise = preciseService();
        PredictionService constant = constantService();

        // 先累积 300 s 观测（30 拍×10 s）让 P_vv 收敛，再比点位：冷启动头两拍的带宽不代表稳态
        for (int step = 0; step <= 30; step++) {
            AisMessage observation = observation(MMSI, ORIGIN_LON, ORIGIN_LAT, step * 10.0);
            precise.refresh(observation);
            constant.refresh(observation);
        }

        PredictionSnapshot preciseSnapshot = precise.snapshotOf(MMSI).orElseThrow();
        PredictionSnapshot constantSnapshot = constant.snapshotOf(MMSI).orElseThrow();

        double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(ORIGIN_LAT));
        double worst = 0.0;
        double worstTau = 0.0;
        for (int index = 0; index < preciseSnapshot.points().size(); index++) {
            ShipTrajPoint left = preciseSnapshot.points().get(index);
            ShipTrajPoint right = constantSnapshot.points().get(index);
            double east = (left.lon() - right.lon()) * metersPerDegLon;
            double north = (left.lat() - right.lat()) * MPD_LAT;
            double distance = Math.hypot(east, north);
            if (distance > worst) {
                worst = distance;
                worstTau = (left.t() - preciseSnapshot.t0Ms()) / 1000.0;
            }
        }

        System.out.printf(java.util.Locale.ROOT,
                "%n[F4] 精算 vs 匀速 点位差异 最大 %.1f m（出现在 τ=%.0f s），τ=0 处 %.1f m%n",
                worst, worstTau, Math.hypot(
                        (preciseSnapshot.points().get(0).lon() - constantSnapshot.points().get(0).lon())
                                * metersPerDegLon,
                        (preciseSnapshot.points().get(0).lat() - constantSnapshot.points().get(0).lat())
                                * MPD_LAT));
        ShipTrajPoint preciseFirst = preciseSnapshot.points().get(0);
        ShipTrajPoint preciseLast = preciseSnapshot.points().get(preciseSnapshot.points().size() - 1);
        System.out.printf(java.util.Locale.ROOT,
                "[F4] 精算 σ∥ %.1f → %.1f m，σ⊥ %.1f → %.1f m，有效期 %d s（匀速版 σ∥ %.1f → %.1f m）%n",
                preciseFirst.sigmaAlong(), preciseLast.sigmaAlong(),
                preciseFirst.sigmaCross(), preciseLast.sigmaCross(),
                preciseSnapshot.validitySec(),
                constantSnapshot.points().get(0).sigmaAlong(),
                constantSnapshot.points().get(constantSnapshot.points().size() - 1).sigmaAlong());

        // 直航场景下两者都退了线性外推，差异应远小于航道尺度（5 km 量级）
        assertTrue(worst < 200.0, "两条实现差得太多，说明精算链路没接在正确状态上：" + worst + " m");
    }

    @Test
    @DisplayName("网兜底：精算输出落在赣江框外 → 本帧回落匀速版并计数")
    void outOfBoxPreciseOutput_shouldFallBackToConstant() {
        PredictionService service = preciseService();
        double farLon = 121.47;
        double farLat = 30.50;

        service.refresh(observation(MMSI, farLon, farLat, 0.0));
        PredictionSnapshot snapshot = service.refresh(observation(MMSI, farLon, farLat, 10.0));

        assertEquals(1L, service.preciseRejectionCount(), "框外输出必须被拦下");
        assertNull(snapshot.zSeries(), "回落匀速版后不得带新息序列");
        assertEquals(900L, snapshot.validitySec(), "回落帧有效期取配置常量");
        List<ShipTrajPoint> points = new ArrayList<>(snapshot.points());
        assertEquals(31, points.size());
        AisMessage lastObservation = observation(MMSI, farLon, farLat, 10.0);
        assertEquals(lastObservation.lon(), points.get(0).lon(), 1e-9,
                "匀速版 τ=0 从本帧观测位置起算");
        assertEquals(lastObservation.lat(), points.get(0).lat(), 1e-9);
    }
}
