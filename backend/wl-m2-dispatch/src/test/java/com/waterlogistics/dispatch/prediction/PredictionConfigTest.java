package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 装配口径单测（M2 · 卢肄文 · 10-23 F4）。
 *
 * <p>不走 Spring 容器，直接调 {@link PredictionConfig} 的两个装配方法：要验的是「开关怎么解析、
 * 回退姿势长什么样」，而不是容器能不能起（后者由 {@code wl-app} 的上下文测试覆盖）。
 */
class PredictionConfigTest {

    private final PredictionConfig config = new PredictionConfig();

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    private final PredictionAssurance assurance =
            new PredictionAssurance(PredictionAssurance.Bounds.demoArea());

    private PredictionProfile profile(String modeCode) {
        return config.predictionProfile(settings, assurance, modeCode,
                50.0, 180.0, 40.0, 300.0, 1.0e-6, 75.0, 6.0, 0.5, 120.0);
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
}
