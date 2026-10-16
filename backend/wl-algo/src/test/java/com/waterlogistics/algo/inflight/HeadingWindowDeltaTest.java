package com.waterlogistics.algo.inflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 《84》§2.5 滑窗平均航向差的完成判据：
 * 「单测：喂一段变向航迹，输出值随变向角度单调变化」。
 */
class HeadingWindowDeltaTest {

    private static final double TANGENT_DEG = 90.0;
    private static final double EPS = 1e-9;

    /**
     * 构造一段"匀速直线预测 + 恒定变向"的航迹窗口。
     *
     * @param turnDeg 每帧实测 COG 相对预测切向的偏角（带符号）
     * @param frames  窗口帧数
     */
    private static List<HeadingSample> constantTurnWindow(double turnDeg, int frames) {
        List<HeadingSample> window = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            double cog = (TANGENT_DEG + turnDeg + 720.0) % 360.0;
            window.add(new HeadingSample(cog, TANGENT_DEG, 5.0, i * 1000L));
        }
        return window;
    }

    /**
     * 角度环绕：§2.5 原文提醒"角度环绕有小坑"。
     * 350° 与 10° 的角差是 −20°，绝不能算成 ±340°。
     */
    @Test
    void normalizeDelta_shouldTakeShortestArcAcross180() {
        assertEquals(-20.0, HeadingWindowDelta.normalizeDeltaDeg(350.0 - 10.0), EPS);
        assertEquals(20.0, HeadingWindowDelta.normalizeDeltaDeg(10.0 - 350.0), EPS);

        // 航向差的实际定义域是 (-360, 360)，全区间扫描必须落在 [-180, 180)
        for (double raw = -359.5; raw <= 359.5; raw += 7.3) {
            double delta = HeadingWindowDelta.normalizeDeltaDeg(raw);
            assertTrue(delta >= -180.0 && delta < 180.0, "raw=" + raw + " 归一化得 " + delta);
        }
    }

    /**
     * 完成判据一：输出值随变向角度单调变化（正向）。
     */
    @Test
    void averageDelta_shouldIncreaseWithTurnAngle() {
        double previous = Double.NEGATIVE_INFINITY;
        for (double turn : new double[]{5.0, 10.0, 15.0, 20.0, 25.0}) {
            HeadingWindowResult result = HeadingWindowDelta.compute(constantTurnWindow(turn, 3));
            assertEquals(turn, result.averageDeg(), EPS);
            assertTrue(result.averageDeg() > previous, "变向 " + turn + "° 应大于上一次");
            previous = result.averageDeg();
        }
    }

    /**
     * 完成判据一（反向）：变向为负时输出为负，且同样随变向角度单调变化。
     */
    @Test
    void averageDelta_shouldBeSignedAndMonotonicForNegativeTurn() {
        double previous = Double.NEGATIVE_INFINITY;
        for (double turn : new double[]{-25.0, -20.0, -15.0, -10.0, -5.0}) {
            HeadingWindowResult result = HeadingWindowDelta.compute(constantTurnWindow(turn, 3));
            assertTrue(result.averageDeg() < 0.0, "反向变向应得负值");
            assertTrue(result.averageDeg() > previous, "变向 " + turn + "° 应大于上一次");
            previous = result.averageDeg();
        }
    }

    /**
     * 整窗跨 ±180 时，累计量仍按最短角差累加。
     */
    @Test
    void accumulate_shouldUseShortestArcForWholeWindow() {
        List<HeadingSample> window = List.of(
                new HeadingSample(350.0, 10.0, 5.0, 0L),
                new HeadingSample(350.0, 10.0, 5.0, 1000L),
                new HeadingSample(350.0, 10.0, 5.0, 2000L));
        HeadingWindowResult result = HeadingWindowDelta.compute(window);
        assertEquals(-20.0, result.averageDeg(), EPS);
        assertEquals(-60.0, result.accumulatedDeg(), EPS);
    }

    /**
     * 平均（§2.5 特征）与累计（§2.2 判据量）相差一个窗口长度因子。
     */
    @Test
    void averageAndAccumulated_shouldDifferByWindowLength() {
        HeadingWindowResult result = HeadingWindowDelta.compute(constantTurnWindow(12.0, 5));
        assertEquals(12.0, result.averageDeg(), EPS);
        assertEquals(60.0, result.accumulatedDeg(), EPS);
        assertEquals(result.accumulatedDeg() / result.effectiveFrames(), result.averageDeg(), EPS);
    }

    /**
     * "窗口内各帧同号"用于滤除钟摆式抖动：持续改向为真，左右摆动为假。
     */
    @Test
    void sameSign_shouldSeparateSteadyTurnFromPendulum() {
        assertTrue(HeadingWindowDelta.compute(constantTurnWindow(15.0, 4)).sameSign());

        List<HeadingSample> pendulum = List.of(
                new HeadingSample(130.0, 90.0, 5.0, 0L),
                new HeadingSample(60.0, 90.0, 5.0, 1000L),
                new HeadingSample(130.0, 90.0, 5.0, 2000L),
                new HeadingSample(60.0, 90.0, 5.0, 3000L));
        HeadingWindowResult result = HeadingWindowDelta.compute(pendulum);
        assertFalse(result.sameSign(), "钟摆式抖动不得判为同号");
        assertEquals(20.0, result.accumulatedDeg(), EPS);
    }

    /**
     * 低速冻结：§2.5 要求"低速时同样冻结"，低于阈值的样本不参与统计。
     */
    @Test
    void lowSpeedSamples_shouldBeFrozenOut() {
        List<HeadingSample> window = List.of(
                new HeadingSample(100.0, 90.0, 0.2, 0L),
                new HeadingSample(100.0, 90.0, 0.3, 1000L),
                new HeadingSample(100.0, 90.0, 5.0, 2000L),
                new HeadingSample(100.0, 90.0, 5.0, 3000L));

        HeadingWindowResult all = HeadingWindowDelta.compute(window);
        assertEquals(4, all.effectiveFrames(), "默认不冻结");

        HeadingWindowResult frozen = HeadingWindowDelta.compute(window, 1.0);
        assertEquals(2, frozen.effectiveFrames(), "低于 1 m/s 的两帧应被剔除");
        assertEquals(10.0, frozen.averageDeg(), EPS);
    }

    /**
     * 空窗口返回零值，累计量为 0，下游不会误判意图变更。
     */
    @Test
    void emptyWindow_shouldReturnZeros() {
        HeadingWindowResult result = HeadingWindowDelta.compute(List.of());
        assertEquals(0.0, result.averageDeg(), EPS);
        assertEquals(0.0, result.accumulatedDeg(), EPS);
        assertEquals(0, result.effectiveFrames());
    }
}
