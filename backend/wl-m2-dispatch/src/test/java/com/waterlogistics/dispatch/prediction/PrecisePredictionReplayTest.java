package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 按 M1 仿真的真实喂法回放（M2 · 卢肄文 · 10-23 F4 的「端到端冒烟」前置）。
 *
 * <p>端到端验收要由组长起四件套跑，但那一步之前有个更便宜的问题值得先答：
 * <b>仿真器那套喂法（1 Hz、8 节、5 m 位置噪声、中途注入转弯）会不会把精算链路打崩</b>。
 * 现有单测都是 10 s 一拍、无噪声、纯直航，覆盖不到这三件事：
 * <ul>
 *   <li><b>1 Hz 密采样</b>：相邻观测间距约 4 m，小于建参考线的最小间距门限（5 m），
 *       冷启动会多耗一两拍——必须确认它只是「多等一拍」，不是「永远建不出线」；</li>
 *   <li><b>位置噪声</b>：噪声进了新息，σ 若因此涨过头会被断言网拦下；</li>
 *   <li><b>中途转弯</b>（仿真有 {@code TurnInjectController}，每次 +10°）：转弯后船会偏离
 *       冷启动时建的那条参考线，横向偏移被夹到半宽——必须确认它不会让点位跳飞、也不会触发断言。</li>
 * </ul>
 *
 * <p>坐标与参数取仿真器真值（{@code application-m1-sim.yml}：{@code 114.30E/30.60N}、8 节、
 * 航向 90°）与 {@code sys_param} 真值（σ_pos 20 m、α∥ 0.05、σ_e 15 m、α⊥ 0.03、1800 s / 60 s），
 * 断言全部落在「不崩、不被拦、σ 仍单调」三件可判定的事上。
 */
class PrecisePredictionReplayTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String MMSI = "123456789";
    private static final double ORIGIN_LON = 114.3000;
    private static final double ORIGIN_LAT = 30.6000;
    private static final double KNOT_TO_MS = 1852.0 / 3600.0;
    private static final double MPD_LAT = 111_320.0;
    private static final double SOG_MS = 8.0 * KNOT_TO_MS;

    /** 仿真 AIS 噪声：{@code noise: 0.00005} 度 ≈ 5.5 m。 */
    private static final double POSITION_NOISE_DEG = 0.00005;

    /** 回放时长与节拍：与 {@code wavenest.sim.tick-millis: 1000} 一致。 */
    private static final int TICKS = 300;

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    private final PredictionAssurance assurance =
            new PredictionAssurance(PredictionAssurance.Bounds.demoArea());

    private PredictionService service() {
        PredictionProfile profile = PredictionProfile.precise(settings, assurance,
                PrecisePredictionEngine.Params.defaults());
        return new PredictionService(settings, new LoggingRecomputeRequester(), event -> { },
                profile, () -> T0 + 3_600_000L);
    }

    @Test
    @DisplayName("★ 仿真喂法回放 300 拍：不崩、不被断言网拦、σ 仍单调")
    void replaySimulatorTicks_shouldStayHealthy() {
        PredictionService service = service();
        Random random = new Random(20261023L);

        double cogDeg = 90.0;
        int preciseFrames = 0;
        PredictionSnapshot last = null;

        for (int tick = 0; tick <= TICKS; tick++) {
            // 仿真里的转弯注入：第 200 拍起航向 +10°（对应 TurnInjectController 的一次注入）
            if (tick == 200) {
                cogDeg += 10.0;
            }

            double east = SOG_MS * tick * Math.sin(Math.toRadians(cogDeg));
            double north = SOG_MS * tick * Math.cos(Math.toRadians(cogDeg));
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(ORIGIN_LAT));

            AisMessage observation = new AisMessage(MMSI, "SIM-SHIP-01",
                    ORIGIN_LON + east / metersPerDegLon + POSITION_NOISE_DEG * random.nextGaussian(),
                    ORIGIN_LAT + north / MPD_LAT + POSITION_NOISE_DEG * random.nextGaussian(),
                    8.0,
                    cogDeg,
                    T0 + tick * 1000L);

            last = service.refresh(observation);
            assertNotNull(last.points(), "第 " + tick + " 拍快照缺失");
            assertEquals(31, last.points().size(), "第 " + tick + " 拍点数不对");

            if (last.zSeries() != null) {
                preciseFrames++;
            }
            assertMonotone(last, tick);
        }

        assertEquals(0L, service.preciseRejectionCount(), "整段回放不得有任何一帧被断言网拦下");
        assertTrue(preciseFrames >= TICKS - 5,
                "精算帧数过少（" + preciseFrames + "/" + TICKS + "），说明多数拍回落了匀速版");

        // 转弯后仍要给出正的有效期，且点位留在演示域内
        assertTrue(last.validitySec() > 0L, "转弯后有效期不得归零");
        ShipTrajPoint tail = last.points().get(last.points().size() - 1);
        assertTrue(assurance.bounds().contains(tail.lon(), tail.lat()),
                "末点落在演示域外：" + tail.lon() + "," + tail.lat());

        System.out.printf(java.util.Locale.ROOT,
                "%n[F4] 仿真回放 %d 拍：精算帧 %d、断言拦截 %d、末帧版本 %d、有效期 %d s、"
                        + "σ∥ %.1f → %.1f m、σ⊥ %.1f → %.1f m、新息 %d 条%n",
                TICKS + 1, preciseFrames, service.preciseRejectionCount(), last.version(),
                last.validitySec(),
                last.points().get(0).sigmaAlong(), tail.sigmaAlong(),
                last.points().get(0).sigmaCross(), tail.sigmaCross(),
                last.zSeries() == null ? 0 : last.zSeries().size());
    }

    private static void assertMonotone(PredictionSnapshot snapshot, int tick) {
        List<ShipTrajPoint> points = snapshot.points();
        ShipTrajPoint previous = points.get(0);
        assertTrue(previous.sigmaAlong() > 0.0 && previous.sigmaCross() > 0.0,
                "第 " + tick + " 拍 σ 非正");
        for (int index = 1; index < points.size(); index++) {
            ShipTrajPoint point = points.get(index);
            assertTrue(point.sigmaAlong() >= previous.sigmaAlong() - 1e-9,
                    "第 " + tick + " 拍 σ∥ 在第 " + index + " 点下降");
            assertTrue(point.sigmaCross() >= previous.sigmaCross() - 1e-9,
                    "第 " + tick + " 拍 σ⊥ 在第 " + index + " 点下降");
            previous = point;
        }
    }
}
