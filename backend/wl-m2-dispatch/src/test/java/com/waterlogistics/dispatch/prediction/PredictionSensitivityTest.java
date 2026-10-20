package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.storage.param.SysParamLoader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预测层<b>参数敏感性测试</b>（M2 · 卢肄文）——《plan 10-19》「补预测参数敏感性测试」。
 *
 * <p>目的：证明预测输出**由参数与输入决定**，改一个参数、输出按预期方向变化——
 * 而不是一组写死的常量。覆盖三类：
 * <ol>
 *   <li><b>模型参数敏感性</b>：σ₀／α 变化 → 不确定性带宽窄与增速按比例变化；</li>
 *   <li><b>时域参数敏感性</b>：horizon／step 变化 → 预测点数变化；</li>
 *   <li><b>sys_param 接线正确性</b>：{@link PredictionConfig} 读的是
 *       《37》§5.14.1 的<b>权威键名</b>，键缺失时走缺省值（不臆造、不静默为 0）。</li>
 * </ol>
 *
 * <p>不依赖 Spring 与数据库。
 */
class PredictionSensitivityTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final double LON = 121.4700;
    private static final double LAT = 30.5000;

    private static final AisMessage SAMPLE =
            new AisMessage("413000001", "测试船", LON, LAT, 10.0, 0.0, T0);

    private static PredictionSnapshot predictWith(PredictionSettings settings) {
        PredictionService service =
                new PredictionService(settings, new LoggingRecomputeRequester());
        return service.refresh(SAMPLE);
    }

    // ───────────────── ① 模型参数敏感性 ─────────────────

    @Test
    @DisplayName("σ∥ 初值加倍 → 各点沿航道 σ 按比例增加（线性可预期）")
    void sigmaAlong_shouldScaleLinearly() {
        PredictionSettings base = new PredictionSettings(20.0, 0.05, 15.0, 0.03, 600L, 60L, 900L);
        PredictionSettings doubled = new PredictionSettings(40.0, 0.05, 15.0, 0.03, 600L, 60L, 900L);

        double firstBase = predictWith(base).points().get(0).sigmaAlong();
        double firstDoubled = predictWith(doubled).points().get(0).sigmaAlong();

        assertEquals(20.0, firstBase, 1e-9);
        assertEquals(40.0, firstDoubled, 1e-9);
        assertEquals(2.0, firstDoubled / firstBase, 1e-9, "σ 初值加倍，首点 σ 也加倍");
    }

    @Test
    @DisplayName("α∥ 增大 → 同一时刻的 σ 更大（增长更快），且差额随时间放大")
    void alphaAlong_shouldAccelerateGrowth() {
        PredictionSettings slow = new PredictionSettings(20.0, 0.05, 15.0, 0.03, 600L, 60L, 900L);
        PredictionSettings fast = new PredictionSettings(20.0, 0.20, 15.0, 0.03, 600L, 60L, 900L);

        List<ShipTrajPoint> slowPoints = predictWith(slow).points();
        List<ShipTrajPoint> fastPoints = predictWith(fast).points();

        // 首点（τ=0）两者相同（只看 σ₀）
        assertEquals(slowPoints.get(0).sigmaAlong(), fastPoints.get(0).sigmaAlong(), 1e-9);

        // 末点（τ=600）差额 = (0.20-0.05)*600 = 90 m
        double slowLast = slowPoints.get(slowPoints.size() - 1).sigmaAlong();
        double fastLast = fastPoints.get(fastPoints.size() - 1).sigmaAlong();
        assertEquals(90.0, fastLast - slowLast, 1e-6, "α 差 0.15 m/s × 600 s = 90 m");

        // 差额随时间单调放大：τ=300（索引 5）的差额 45 m < τ=600（末点）的 90 m
        assertTrue((fastPoints.get(5).sigmaAlong() - slowPoints.get(5).sigmaAlong())
                < (fastLast - slowLast), "越往后差额越大");
    }

    @Test
    @DisplayName("横向 σ 独立于沿航道 σ（两通道不串）")
    void sigmaCross_shouldBeIndependentOfAlong() {
        PredictionSettings narrowCross =
                new PredictionSettings(20.0, 0.05, 5.0, 0.03, 600L, 60L, 900L);
        PredictionSettings wideCross =
                new PredictionSettings(20.0, 0.05, 50.0, 0.03, 600L, 60L, 900L);

        assertEquals(predictWith(narrowCross).points().get(0).sigmaAlong(),
                predictWith(wideCross).points().get(0).sigmaAlong(), 1e-9,
                "改横向 σ 不应影响沿航道 σ");
        assertEquals(5.0, predictWith(narrowCross).points().get(0).sigmaCross(), 1e-9);
        assertEquals(50.0, predictWith(wideCross).points().get(0).sigmaCross(), 1e-9);
    }

    // ───────────────── ② 时域参数敏感性 ─────────────────

    @Test
    @DisplayName("horizon / step 变化 → 预测点数按 horizon/step+1 变化")
    void horizonAndStep_shouldDrivePointCount() {
        assertEquals(11, predictWith(
                new PredictionSettings(20, 0.05, 15, 0.03, 600L, 60L, 900L)).points().size());
        assertEquals(21, predictWith(
                new PredictionSettings(20, 0.05, 15, 0.03, 1200L, 60L, 900L)).points().size());
        assertEquals(6, predictWith(
                new PredictionSettings(20, 0.05, 15, 0.03, 600L, 120L, 900L)).points().size());
    }

    @Test
    @DisplayName("validitySec 变化 → 过期判定边界随之移动")
    void validity_shouldDriveStaleness() {
        PredictionSnapshot shortLived = predictWith(
                new PredictionSettings(20, 0.05, 15, 0.03, 600L, 60L, 300L));
        PredictionSnapshot longLived = predictWith(
                new PredictionSettings(20, 0.05, 15, 0.03, 600L, 60L, 1800L));

        assertTrue(shortLived.isStale(T0 + 400_000L), "有效期 300 s，400 s 后应过期");
        assertEquals(false, longLived.isStale(T0 + 400_000L), "有效期 1800 s，400 s 未过期");
    }

    @Test
    @DisplayName("船速变化 → 预测里程按比例变化（复核既有口径）")
    void sog_shouldDriveMileage() {
        double slow = reachMeters(new AisMessage("413000001", "t", LON, LAT, 5.0, 0.0, T0));
        double fast = reachMeters(new AisMessage("413000001", "t", LON, LAT, 20.0, 0.0, T0));

        assertEquals(4.0, fast / slow, 0.01, "20 节 vs 5 节，里程应约 4 倍");
        assertNotEquals(slow, fast);
    }

    private static double reachMeters(AisMessage sample) {
        PredictionService service = new PredictionService(
                new PredictionSettings(20, 0.05, 15, 0.03, 600L, 60L, 900L),
                new LoggingRecomputeRequester());
        List<ShipTrajPoint> points = service.refresh(sample).points();
        ShipTrajPoint last = points.get(points.size() - 1);
        double dNorth = (last.lat() - LAT) * ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        double dEast = (last.lon() - LON) * ConstantVelocityPredictor.METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(LAT));
        return Math.hypot(dNorth, dEast);
    }

    // ───────────────── ③ sys_param 接线正确性 ─────────────────

    @Test
    @DisplayName("★ PredictionConfig 读的是 37 §5.14.1 的权威键名（不是臆造键）")
    void config_shouldUseAuthoritativeKeys() {
        assertEquals("sigmaPosM", PredictionConfig.KEY_SIGMA_POS_M);
        assertEquals("sigmaEM", PredictionConfig.KEY_SIGMA_E_M);
        assertEquals("alphaAlongMs", PredictionConfig.KEY_ALPHA_ALONG_MS);
        assertEquals("alphaCrossMs", PredictionConfig.KEY_ALPHA_CROSS_MS);
        assertEquals("horizonSec", PredictionConfig.KEY_HORIZON_SEC);
    }

    @Test
    @DisplayName("★ 键缺失时走缺省值（不静默为 0）——空参数表下装配仍可用")
    void config_shouldFallBackToDefaultsWhenKeysMissing() {
        // 不调 refresh()，缓存为空 → getDouble/getInt 全部返回传入的默认值
        SysParamLoader emptyLoader = new SysParamLoader(null);

        PredictionSettings settings = new PredictionConfig().predictionSettings(emptyLoader);

        assertEquals(20.0, settings.sigma0AlongM(), 1e-9);
        assertEquals(0.05, settings.alphaAlongMs(), 1e-9);
        assertEquals(15.0, settings.sigma0CrossM(), 1e-9);
        assertEquals(0.03, settings.alphaCrossMs(), 1e-9);
        assertEquals(1800L, settings.horizonSec());
        assertEquals(PredictionConfig.DEFAULT_STEP_SEC, settings.stepSec());
        assertEquals(PredictionConfig.DEFAULT_VALIDITY_SEC, settings.validitySec());
    }

    @Test
    @DisplayName("装配出的参数能直接驱动预测（端到端：sys_param → 快照）")
    void config_shouldProduceUsableParams() {
        SysParamLoader emptyLoader = new SysParamLoader(null);
        PredictionSettings settings = new PredictionConfig().predictionSettings(emptyLoader);

        PredictionService service =
                new PredictionService(settings, new LoggingRecomputeRequester());
        PredictionSnapshot snapshot = service.refresh(SAMPLE);

        // 缺省 horizon=1800 s、step=60 s → 1800/60 + 1 = 31 点
        assertEquals(31, snapshot.points().size());
        assertEquals(PredictionConfig.DEFAULT_VALIDITY_SEC, snapshot.validitySec(),
                "有效期取 DEFAULT_VALIDITY_SEC（sys_param 暂无此键）");
        assertEquals(20.0, snapshot.points().get(0).sigmaAlong(), 1e-9,
                "首点 σ 应等于 sys_param 的 sigmaPosM 缺省值 20");
    }
}
