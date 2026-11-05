package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段限速解析单测（11-03 M4 · 吴可欣）。
 *
 * <p>只验「配置串 → 分段表 → 弧长查询」这条纯函数链，不碰容器、不碰引擎。
 * 引擎侧是否真用上这个查询，由后续引擎接入（卢肄文）的用例覆盖。
 */
class CenterlineSpeedLimitLookupTest {

    /** 不限速标记，与 {@code MultiStepPredictor.SpeedLimitLookup.unbounded()} 同值。 */
    private static final double UNBOUNDED = Double.MAX_VALUE;

    @Test
    @DisplayName("落在分段内返回该段限速")
    void sInsideSegment_shouldReturnSegmentLimit() {
        CenterlineSpeedLimitLookup lookup =
                CenterlineSpeedLimitLookup.parse("0,2000,4.0;2000,5000,3.0");

        assertEquals(4.0, lookup.speedLimitMs(0.0), 1e-9, "fromS 左闭命中首段");
        assertEquals(4.0, lookup.speedLimitMs(1000.0), 1e-9);
        assertEquals(3.0, lookup.speedLimitMs(2000.0), 1e-9, "第二段 fromS 命中");
        assertEquals(3.0, lookup.speedLimitMs(4999.0), 1e-9);
    }

    @Test
    @DisplayName("弧长超出所有段（含 toS 右开）不限速")
    void sOutsideSegments_shouldBeUnbounded() {
        CenterlineSpeedLimitLookup lookup =
                CenterlineSpeedLimitLookup.parse("0,2000,4.0;2000,5000,3.0");

        assertEquals(UNBOUNDED, lookup.speedLimitMs(-1.0), 0.0, "首段 fromS 之前");
        assertEquals(UNBOUNDED, lookup.speedLimitMs(5000.0), 0.0, "末段 toS 右开，落在段外");
        assertEquals(UNBOUNDED, lookup.speedLimitMs(9999.0), 0.0);
    }

    @Test
    @DisplayName("空串或 null → 恒不限速（退化为现状）")
    void blankOrNull_shouldBeUnbounded() {
        assertEquals(UNBOUNDED, CenterlineSpeedLimitLookup.parse(null).speedLimitMs(0.0), 0.0);
        assertEquals(UNBOUNDED, CenterlineSpeedLimitLookup.parse("").speedLimitMs(0.0), 0.0);
        assertEquals(UNBOUNDED, CenterlineSpeedLimitLookup.parse("   ").speedLimitMs(0.0), 0.0);
    }

    @Test
    @DisplayName("非法配置（项数错／非正限速／乱序重叠）→ 恒不限速兜底")
    void invalidSpec_shouldFallBackToUnbounded() {
        assertTrue(isUnbounded("0,2000"), "项数不足应回落");
        assertTrue(isUnbounded("0,2000,4.0,9.9"), "项数过多应回落");
        assertTrue(isUnbounded("2000,1000,4.0"), "fromS >= toS 应回落");
        assertTrue(isUnbounded("0,2000,0"), "限速非正应回落");
        assertTrue(isUnbounded("0,2000,-3.0"), "限速为负应回落");
        assertTrue(isUnbounded("0,3000,4.0;2000,5000,3.0"), "段重叠应回落");
        assertTrue(isUnbounded("3000,4000,4.0;0,2000,3.0"), "乱序应回落");
    }

    @Test
    @DisplayName("单段配置可用")
    void singleSegment_shouldWork() {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse("0,5780,2.5");

        assertEquals(2.5, lookup.speedLimitMs(0.0), 1e-9);
        assertEquals(2.5, lookup.speedLimitMs(5779.9), 1e-9);
        assertEquals(UNBOUNDED, lookup.speedLimitMs(5780.0), 0.0);
    }

    @Test
    @DisplayName("切换点：各段 fromS／toS 升序去重，空配置为空数组")
    void switchPoints_shouldListSegmentEdges() {
        // 段间留空隙：2000 是「退出限速段」、3000 是「进入限速段」，两个端点都是限速变化处。
        assertArrayEquals(new double[] {0.0, 2000.0, 3000.0, 5000.0},
                CenterlineSpeedLimitLookup.parse("0,2000,4.0;3000,5000,3.0").switchPointsM(),
                1e-9, "段间留空隙时空隙两端的端点都要保留");

        // 首尾相接：前段 toS 与后段 fromS 重合，只有一个真实切换点。
        assertArrayEquals(new double[] {0.0, 2000.0, 5000.0},
                CenterlineSpeedLimitLookup.parse("0,2000,4.0;2000,5000,3.0").switchPointsM(),
                1e-9, "首尾相接的两段只留一个切换点");

        // 汇合复跑用的配置：两段同值，切换点仍在 3000，预测器照样据此切子步。
        assertArrayEquals(new double[] {0.0, 3000.0, 5780.0},
                CenterlineSpeedLimitLookup.parse("0,3000,2.5;3000,5780,2.5").switchPointsM(),
                1e-9);

        assertEquals(0, CenterlineSpeedLimitLookup.parse("").switchPointsM().length,
                "空配置没有切换点");
        assertEquals(0, CenterlineSpeedLimitLookup.parse(null).switchPointsM().length,
                "null 配置没有切换点");
    }

    private static boolean isUnbounded(String spec) {
        return CenterlineSpeedLimitLookup.parse(spec).speedLimitMs(0.0) == UNBOUNDED;
    }
}
