package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionSettings} 的装配期校验（11-14 序号 2-6 · MEDIUM.48）。
 *
 * <p>此前本记录体不收任何约束：{@code sys_param} 里写了 {@code sigmaPosM=0}、
 * {@code alpha} 为负、或 {@code horizonSec < stepSec}，Spring 装配照样成功，
 * 直到第一笔预测请求调 {@code toParams()} 才抛——错误现场离病根隔着一次启动与一次业务请求。
 *
 * <p>这里逐条验「坏值在构造时就炸」，并确认判据来自算法层
 * {@code PredictionParams}（同一套不变式，不在模块层另写一份）。
 */
class PredictionSettingsTest {

    /** 一组完全合法的参数，各用例只改其中一项。 */
    private static final double SIGMA_ALONG = 20.0;
    private static final double ALPHA_ALONG = 0.05;
    private static final double SIGMA_CROSS = 15.0;
    private static final double ALPHA_CROSS = 0.03;
    private static final long HORIZON = 1800L;
    private static final long STEP = 60L;
    private static final long VALIDITY = 900L;

    private static PredictionSettings settings(double sigmaAlong, double alphaAlong,
            double sigmaCross, double alphaCross, long horizon, long step, long validity) {
        return new PredictionSettings(sigmaAlong, alphaAlong, sigmaCross, alphaCross,
                horizon, step, validity);
    }

    @Test
    @DisplayName("合法参数照常构造，并可转为算法层参数")
    void validSettings_shouldBuild() {
        PredictionSettings settings = settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS,
                HORIZON, STEP, VALIDITY);

        assertEquals(SIGMA_ALONG, settings.toParams().sigma0AlongM());
        assertEquals(31, settings.toParams().pointCount(), "1800/60 + 1");
    }

    @Test
    @DisplayName("★ MEDIUM.48：σ 初值非正 ⇒ 构造即抛（装配期失败，不是请求期）")
    void nonPositiveSigma_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(0.0, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS, HORIZON, STEP, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, -1.0, ALPHA_CROSS, HORIZON, STEP, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings(Double.NaN, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS,
                        HORIZON, STEP, VALIDITY));
    }

    @Test
    @DisplayName("★ MEDIUM.48：σ 增长率为负 ⇒ 构造即抛")
    void negativeAlpha_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, -0.01, SIGMA_CROSS, ALPHA_CROSS, HORIZON, STEP, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, -0.01, HORIZON, STEP, VALIDITY));
    }

    @Test
    @DisplayName("★ MEDIUM.48：时域／步长／有效期非正 ⇒ 构造即抛")
    void nonPositiveTimings_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS, 0L, STEP, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS, HORIZON, 0L, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS, HORIZON, STEP, 0L));
    }

    @Test
    @DisplayName("★ MEDIUM.48：步长大于时域 ⇒ 构造即抛（点列会只剩 1 点，下游静默失真）")
    void stepGreaterThanHorizon_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(SIGMA_ALONG, ALPHA_ALONG, SIGMA_CROSS, ALPHA_CROSS, 60L, 120L, VALIDITY));
    }
}
