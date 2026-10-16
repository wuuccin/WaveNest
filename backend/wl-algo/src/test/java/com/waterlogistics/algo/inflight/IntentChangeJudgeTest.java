package com.waterlogistics.algo.inflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 《84》§2.2 改向判据的完成判据：
 * 「改阈值/改变向角度，判定结果跟着变（真算法判据）」。
 */
class IntentChangeJudgeTest {

    private static final double TANGENT_DEG = 90.0;
    private static final double EPS = 1e-9;

    private static List<HeadingSample> constantTurnWindow(double turnDeg, int frames) {
        List<HeadingSample> window = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            double cog = (TANGENT_DEG + turnDeg + 720.0) % 360.0;
            window.add(new HeadingSample(cog, TANGENT_DEG, 5.0, i * 1000L));
        }
        return window;
    }

    /**
     * 完成判据二（一半）：阈值改大改小，判定结果必须跟着变。
     */
    @Test
    void verdict_shouldFollowThreshold() {
        HeadingWindowResult result = HeadingWindowDelta.compute(constantTurnWindow(10.0, 3));
        assertEquals(30.0, result.accumulatedDeg(), EPS, "Θ(t) 应为 10°×3");

        IntentChangeSignal high = IntentChangeJudge.judge(result, 40.0);
        assertFalse(high.changed(), "阈值 40° > Θ=30°，不得判变更");
        assertFalse(high.persistent());

        IntentChangeSignal low = IntentChangeJudge.judge(result, 20.0);
        assertTrue(low.changed(), "阈值 20° < Θ=30°，应判变更");
        assertTrue(low.persistent());
    }

    /**
     * 完成判据二（另一半）：变向角度改大改小，判定结果必须跟着变。
     */
    @Test
    void verdict_shouldFollowTurnAngle() {
        double thetaTh = IntentChangeJudge.SUGGESTED_THETA_TH_DEG;

        IntentChangeSignal small = IntentChangeJudge.judge(
                HeadingWindowDelta.compute(constantTurnWindow(5.0, 3)), thetaTh);
        assertFalse(small.changed(), "变向 5°×3=15° 未过 20° 阈值");
        assertFalse(small.persistent());

        IntentChangeSignal large = IntentChangeJudge.judge(
                HeadingWindowDelta.compute(constantTurnWindow(10.0, 3)), thetaTh);
        assertTrue(large.changed(), "变向 10°×3=30° 已过 20° 阈值");
        assertTrue(large.persistent());
    }

    /**
     * §2.2 的"窗口内各帧同号"是滤抖条件：
     * 钟摆式抖动即便累计量过阈值，也不得判"持续成立"。
     */
    @Test
    void pendulum_shouldNotBePersistentEvenIfOverThreshold() {
        List<HeadingSample> pendulum = List.of(
                new HeadingSample(130.0, 90.0, 5.0, 0L),
                new HeadingSample(60.0, 90.0, 5.0, 1000L),
                new HeadingSample(130.0, 90.0, 5.0, 2000L),
                new HeadingSample(60.0, 90.0, 5.0, 3000L));
        HeadingWindowResult result = HeadingWindowDelta.compute(pendulum);

        IntentChangeSignal signal = IntentChangeJudge.judge(result, 5.0);
        assertTrue(signal.changed(), "累计量 Θ=20° 确实过 5° 阈值");
        assertFalse(signal.persistent(), "但各帧异号，属钟摆抖动，不得判持续成立");
        assertFalse(signal.sameSign());
    }

    /**
     * 持续成立蕴含已过阈值：persistent ⇒ changed 恒成立。
     */
    @Test
    void persistent_shouldAlwaysImplyChanged() {
        for (double turn : new double[]{1.0, 5.0, 10.0, 20.0}) {
            for (double thetaTh : new double[]{5.0, 15.0, 30.0, 60.0}) {
                IntentChangeSignal signal = IntentChangeJudge.judge(
                        HeadingWindowDelta.compute(constantTurnWindow(turn, 3)), thetaTh);
                assertFalse(signal.persistent() && !signal.changed(),
                        "turn=" + turn + " thetaTh=" + thetaTh);
            }
        }
    }

    /**
     * 建议初值取自《84》§8 参数名目表：改向累计航向偏量 Θ_th = 20°。
     */
    @Test
    void suggestedThreshold_shouldMatch84Section8() {
        assertEquals(20.0, IntentChangeJudge.SUGGESTED_THETA_TH_DEG, EPS);
    }
}
