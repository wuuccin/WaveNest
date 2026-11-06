package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段限速解析的<b>降级契约</b>（11-06 序号 2 · 卢肄文）。
 *
 * <p>本类只验一条：配置写坏必须<b>降级为不限速</b>，不得把异常抛到装配方。
 * 类注释承诺「解析失败时返回兜底」，而数字段写坏（{@code "0,2000,abc"}）原先会抛
 * {@link NumberFormatException} 冒到 {@code @Bean} 方法，表现为「后端起不来」——
 * 与注释相悖，且现场很难一眼定位到这一行 yml。
 */
class CenterlineSpeedLimitLookupFallbackTest {

    @Test
    @DisplayName("数字段写坏：降级为恒不限速，不抛异常")
    void malformedNumber_shouldFallBackToUnbounded() {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse("0,2000,abc");

        assertTrue(lookup.isUnbounded(), "非法数字应整体降级为不限速");
        assertEquals(Double.MAX_VALUE, lookup.speedLimitMs(1000.0), 0.0);
        assertEquals(0.0, lookup.maxCoveredS(), 0.0);
    }

    @Test
    @DisplayName("段数不足／非有限数：同样降级为不限速")
    void malformedShapeOrNonFinite_shouldFallBackToUnbounded() {
        assertTrue(CenterlineSpeedLimitLookup.parse("0,2000").isUnbounded(), "段数不足");
        assertTrue(CenterlineSpeedLimitLookup.parse("0,2000,NaN").isUnbounded(), "限速非有限");
        assertTrue(CenterlineSpeedLimitLookup.parse("0,Infinity,4.0").isUnbounded(), "上界非有限");
        assertTrue(CenterlineSpeedLimitLookup.parse("4000,2000,4.0").isUnbounded(), "区间倒挂");
    }

    @Test
    @DisplayName("合法配置：isUnbounded 为假，maxCoveredS 取末段 toS")
    void validSpec_shouldReportCoverage() {
        CenterlineSpeedLimitLookup lookup =
                CenterlineSpeedLimitLookup.parse("0,2000,4.5;2000,4000,4.5;4000,5780,2.5");

        assertTrue(!lookup.isUnbounded(), "合法配置不应判为不限速");
        assertEquals(5780.0, lookup.maxCoveredS(), 1e-9);
    }
}
