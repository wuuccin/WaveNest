package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NavPhaseTracker} 单测（A 组 · {@code 80} §3.2.8 的 {@code navPhase} 四态）。
 *
 * <p>验证点：三重条件（速度阈值 ＋ 持续时间 ＋ 位移上限）、<b>瞬时抖动不置位</b>、
 * 上行船（{@code v_s < 0}）按 {@code |v_s|} 判定、减速用缺口比而非绝对速度、
 * 停车→锚泊→复航的转移。
 */
class NavPhaseTrackerTest {

    private static final long T0_MS = 1_700_000_000_000L;

    private static NavPhaseTracker.Params params() {
        return NavPhaseTracker.Params.defaults();   // vStop=0.5, vHys=0.2, ρ=0.25, tStop=180, tRa=60, L=40
    }

    private static NavPhaseTracker.Observation obs(double tSec, double s, double v,
            double nominal, boolean inPort) {
        return new NavPhaseTracker.Observation(T0_MS + Math.round(tSec * 1000.0), s, v, nominal, inPort);
    }

    /** 以 10 s 为步长喂 {@code count} 帧，返回最后一帧后的阶段。 */
    private static NavPhaseTracker.Phase feed(NavPhaseTracker tracker, double startSec,
            int count, java.util.function.DoubleUnaryOperator vOfSec,
            java.util.function.DoubleUnaryOperator sOfSec, boolean inPort) {
        NavPhaseTracker.Phase phase = null;
        for (int i = 0; i < count; i++) {
            double tSec = startSec + i * 10.0;
            phase = tracker.update(obs(tSec, sOfSec.applyAsDouble(tSec),
                    vOfSec.applyAsDouble(tSec), 3.0, inPort));
        }
        return phase;
    }

    @Test
    @DisplayName("稳定巡航 → 在航（缺口比未超阈）")
    void steadyCruise_shouldStayUnderway() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        NavPhaseTracker.Phase phase = feed(tracker, 0, 20,
                t -> 2.8, t -> 2.8 * t, false);
        assertEquals(NavPhaseTracker.Phase.UNDERWAY, phase);
        assertFalse(tracker.isStoppedLike());
    }

    @Test
    @DisplayName("上行船 v_s 持续为负：按 |v_s| 判定，不判停车（内河双向航道正常工况）")
    void upboundShip_shouldBeJudgedByAbsoluteSpeed() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        // 上行 2.8 m/s，s 递减
        NavPhaseTracker.Phase phase = feed(tracker, 0, 20, t -> -2.8, t -> 5000.0 - 2.8 * t, false);
        assertEquals(NavPhaseTracker.Phase.UNDERWAY, phase);
        assertFalse(tracker.isStoppedLike(), "上行船不得被判为停车");
    }

    @Test
    @DisplayName("减速：缺口比超阈但速度未达停车条件 → 减速（不是停车）")
    void decelerated_shouldBecomeSlowedNotStopped() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        // 2.0 / 3.0 = 0.667 → ρ = 0.333 > 0.25，且 2.0 > vStop
        NavPhaseTracker.Phase phase = feed(tracker, 0, 20, t -> 2.0, t -> 2.0 * t, false);
        assertEquals(NavPhaseTracker.Phase.SLOWED, phase);
        assertFalse(tracker.isStoppedLike());
    }

    @Test
    @DisplayName("瞬时抖动（单帧掉到 0.1 m/s 随即恢复）不置位停车")
    void transientDip_shouldNotStop() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        feed(tracker, 0, 5, t -> 2.8, t -> 2.8 * t, false);
        tracker.update(obs(50, 140.0, 0.1, 3.0, false));
        NavPhaseTracker.Phase phase = feed(tracker, 60, 10, t -> 2.8, t -> 168.0 + 2.8 * (t - 60), false);

        assertEquals(NavPhaseTracker.Phase.UNDERWAY, phase, "单帧尖峰不得触发停车");
    }

    @Test
    @DisplayName("持续停车：|v|<vStop 且持续 tStopSec 且窗口位移 < 一船长 → 停车")
    void sustainedStop_shouldBecomeStopped() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        // 0.1 m/s × 180 s = 18 m < 40 m，三重条件同时满足
        NavPhaseTracker.Phase phase = feed(tracker, 0, 25, t -> 0.1, t -> 1000.0 + 0.1 * t, false);
        assertEquals(NavPhaseTracker.Phase.STOPPED, phase);
        assertTrue(tracker.isStoppedLike());
    }

    @Test
    @DisplayName("慢但仍在走：0.4 m/s × 180 s = 72 m > 一船长 → 不判停车（区分过闸／待闸）")
    void slowButMoving_shouldNotBecomeStopped() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        NavPhaseTracker.Phase phase = feed(tracker, 0, 25, t -> 0.4, t -> 1000.0 + 0.4 * t, false);
        assertFalse(tracker.isStoppedLike(),
                "位移已超一船长，属临时减速（过闸／待闸），不得判停车");
        assertEquals(NavPhaseTracker.Phase.SLOWED, phase);
    }

    @Test
    @DisplayName("停车 → 泊入锚地（多边形内持续 tRaSec）→ 锚泊")
    void stoppedThenInPort_shouldBecomeAnchored() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        feed(tracker, 0, 25, t -> 0.1, t -> 1000.0, false);
        assertEquals(NavPhaseTracker.Phase.STOPPED, tracker.phase());

        NavPhaseTracker.Phase phase = feed(tracker, 250, 10, t -> 0.05, t -> 1000.0, true);
        assertEquals(NavPhaseTracker.Phase.ANCHORED, phase);
    }

    @Test
    @DisplayName("停车 → 速度回升至 vStop + vHys 以上 → 在航")
    void stoppedThenRecovered_shouldReturnToUnderway() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        feed(tracker, 0, 25, t -> 0.1, t -> 1000.0 + 0.1 * t, false);
        assertEquals(NavPhaseTracker.Phase.STOPPED, tracker.phase());

        // 复航速度取 2.8 m/s：相对 3.0 m/s 的名义速度缺口比仅 0.067，才不会被判回「减速」
        NavPhaseTracker.Phase phase = feed(tracker, 250, 5, t -> 2.8, t -> 1002.5 + 2.8 * (t - 250), false);
        assertEquals(NavPhaseTracker.Phase.UNDERWAY, phase);
    }

    @Test
    @DisplayName("锚泊 → 离开多边形且速度恢复 → 在航")
    void anchoredThenLeftPort_shouldReturnToUnderway() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        feed(tracker, 0, 25, t -> 0.1, t -> 1000.0, false);
        feed(tracker, 250, 10, t -> 0.05, t -> 1000.0, true);
        assertEquals(NavPhaseTracker.Phase.ANCHORED, tracker.phase());

        // 复航速度取 2.8 m/s，缺口比 0.067 < ρ 阈值，稳定判回「在航」
        NavPhaseTracker.Phase phase = feed(tracker, 350, 5, t -> 2.8, t -> 1000.0 + 2.8 * (t - 350), false);
        assertEquals(NavPhaseTracker.Phase.UNDERWAY, phase);
    }

    @Test
    @DisplayName("速度缺口比：夹到 [0,1]，名义速度为 0 时不除零")
    void speedDeficitRatio_shouldBeSafe() {
        assertEquals(0.0, NavPhaseTracker.speedDeficitRatio(2.0, 0.0), 1e-12);
        assertEquals(0.0, NavPhaseTracker.speedDeficitRatio(5.0, 3.0), 1e-12, "超速时缺口比夹到 0");
        assertEquals(0.5, NavPhaseTracker.speedDeficitRatio(1.5, 3.0), 1e-12);
        assertEquals(1.0, NavPhaseTracker.speedDeficitRatio(0.0, 3.0), 1e-12);
    }

    @Test
    @DisplayName("时间未前进要抛异常（与横向滤波器口径一致）")
    void timeNotAdvancing_shouldThrow() {
        NavPhaseTracker tracker = new NavPhaseTracker(params());
        tracker.update(obs(10, 0.0, 2.0, 3.0, false));
        assertThrows(IllegalStateException.class, () -> tracker.update(obs(5, 0.0, 2.0, 3.0, false)));
    }

    @Test
    @DisplayName("四态中文标记与参数校验")
    void phaseLabelsAndValidation() {
        assertEquals("在航", NavPhaseTracker.Phase.UNDERWAY.label());
        assertEquals("减速", NavPhaseTracker.Phase.SLOWED.label());
        assertEquals("停车", NavPhaseTracker.Phase.STOPPED.label());
        assertEquals("锚泊", NavPhaseTracker.Phase.ANCHORED.label());

        assertThrows(IllegalArgumentException.class,
                () -> new NavPhaseTracker.Params(0.5, 0.2, 1.5, 180.0, 60.0, 40.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NavPhaseTracker.Params(0.5, 0.2, 0.25, 180.0, 60.0, -1.0));
    }
}
