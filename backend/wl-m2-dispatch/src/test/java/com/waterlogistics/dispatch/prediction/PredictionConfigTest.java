package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.storage.entity.SysParamEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.SysParamMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 装配口径单测（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>不走 Spring 容器，直接调 {@link PredictionConfig} 的装配方法：要验的是「开关怎么解析、
 * 回退姿势长什么样、参数键怎么落到引擎」，而不是容器能不能起（后者由 {@code wl-app} 的
 * 上下文测试覆盖）。
 */
class PredictionConfigTest {

    private final PredictionConfig config = new PredictionConfig();

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    private final PredictionAssurance assurance =
            new PredictionAssurance(PredictionAssurance.Bounds.demoArea());

    /** 空参数表（等价「键未登记」）：所有取值走缺省。 */
    private final SysParamLoader emptyParams = loaderWith();

    private PredictionProfile profile(String modeCode) {
        return config.predictionProfile(settings, assurance, emptyParams, modeCode,
                50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0, "",
                SpeedLimitLookup.unbounded());
    }

    @Test
    @DisplayName("中心线配置：给出顶点串时仍组装精算画像")
    void centerlineSpec_shouldStillAssemblePreciseProfile() {
        PredictionProfile profile = config.predictionProfile(settings, assurance, emptyParams,
                "precise",
                50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0,
                "115.852,28.646;115.879,28.692", SpeedLimitLookup.unbounded());

        assertEquals(PredictionMode.PRECISE, profile.mode());
        assertNotNull(profile.engine());
    }

    @Test
    @DisplayName("★ G1：组装的引擎读到 sys_param 的 predAlongSpeedBiasMs（缺省 0 时为 0）")
    void alongSpeedBias_shouldComeFromSysParam() {
        // 键未登记：偏置为 0，预测速度原样返回（接入不改变现状）
        assertEquals(4.0, profile("precise").engine().alongSpeedForPrediction(4.0), 0.0);

        // 键已回写：偏置当场进入推进速度
        SysParamLoader withBias = loaderWith(
                PredictionCalibrationService.KEY_ALONG_SPEED_BIAS, "0.35");
        PredictionProfile biased = config.predictionProfile(settings, assurance, withBias,
                "precise", 50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0, "",
                SpeedLimitLookup.unbounded());
        assertEquals(4.35, biased.engine().alongSpeedForPrediction(4.0), 1e-12,
                "sys_param 里的偏置没有进引擎——读取点又断了");
    }

    @Test
    @DisplayName("缺省档：precise → 精算画像带引擎")
    void preciseMode_shouldCarryEngine() {
        PredictionProfile profile = profile("precise");

        assertEquals(PredictionMode.PRECISE, profile.mode());
        assertNotNull(profile.engine());
        assertNotNull(profile.assurance());
    }

    @Test
    @DisplayName("★ 回退档：constant → 匀速画像无引擎（五分钟内可切回）")
    void constantMode_shouldDropEngine() {
        PredictionProfile profile = profile("constant");

        assertEquals(PredictionMode.CONSTANT, profile.mode());
        assertNull(profile.engine(), "匀速版不建精算引擎");
        assertNotNull(profile.assurance(), "回退姿态也要保留断言网");
    }

    @Test
    @DisplayName("写错取值 → 回落匀速版（不因一个字母起不来）")
    void unknownMode_shouldFallBackToConstant() {
        assertEquals(PredictionMode.CONSTANT, profile("precise-typo").mode());
        assertEquals(PredictionMode.CONSTANT, profile(null).mode());
    }

    // ───────────── 夹具 ─────────────

    /**
     * 构造一个只读的参数加载器。
     *
     * <p>不走 Mockito（本仓测试依赖只有 JUnit Jupiter），用动态代理顶掉 {@link SysParamMapper}：
     * {@link SysParamLoader#refresh()} 只调 {@code selectList}，其余方法一被调用就抛，
     * 免得将来有人顺手在装配路径上加了写库调用却没人发现。
     *
     * @param keyValues 交替的「键、值」，如 {@code "predAlongSpeedBiasMs", "0.35"}
     */
    private static SysParamLoader loaderWith(String... keyValues) {
        List<SysParamEntity> rows = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            SysParamEntity row = new SysParamEntity();
            row.setParamKey(keyValues[i]);
            row.setParamValue(keyValues[i + 1]);
            rows.add(row);
        }
        SysParamMapper mapper = (SysParamMapper) Proxy.newProxyInstance(
                PredictionConfigTest.class.getClassLoader(),
                new Class<?>[] {SysParamMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return rows;
                    }
                    throw new UnsupportedOperationException(
                            "装配路径不该调用 " + method.getName() + "（只读参数表）");
                });
        SysParamLoader loader = new SysParamLoader(mapper);
        loader.refresh();
        return loader;
    }
}
