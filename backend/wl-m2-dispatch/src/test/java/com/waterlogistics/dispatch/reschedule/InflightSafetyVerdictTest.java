package com.waterlogistics.dispatch.reschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.AlertLevel;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 气象超限 → 在飞安全复核的判档（11-14 序号 1-1 契约载体）。
 *
 * <p>钉住三件事：
 * <ul>
 *   <li>三条判档按序命中：解除 → 继续、严重 → 终止、警告 → 调整；</li>
 *   <li>六类不可判入参返回空结论，<b>不兜成「继续」</b>；</li>
 *   <li>构造期校验只在「必然错误」处拒绝，可空字段（{@code orderId}）不拒绝。</li>
 * </ul>
 */
class InflightSafetyVerdictTest {

    private static final long OBSERVED_AT = 1_763_000_000_000L;

    private static InflightSafetyRequest request(AlertLevel level, double wind, boolean cleared) {
        return new InflightSafetyRequest("EV-WIND-1", InflightSafetyTrigger.AIRSPACE_OR_WEATHER,
                "ORD-1", "UAV-1", wind, level, 12, OBSERVED_AT, cleared);
    }

    @Test
    void judge_shouldAdjustOnWarningLevel() {
        Optional<InflightSafetyVerdict> verdict =
                InflightSafetyVerdict.judge(request(AlertLevel.WARN, 9.5, false));

        assertTrue(verdict.isPresent(), "警告级必须给出结论，不能空");
        assertEquals(InflightSafetyVerdict.Verdict.ADJUST, verdict.get().verdict());
        assertEquals("在飞安全复核-调整", verdict.get().verdict().getOpType());
        assertTrue(verdict.get().riskMarked(), "调整档构成在飞风险，须置风险标记");
        assertTrue(verdict.get().reason().contains("9.5"), "判档说明须带风速原值，供 task_log 追溯");
    }

    @Test
    void judge_shouldTerminateOnCriticalLevel() {
        Optional<InflightSafetyVerdict> verdict =
                InflightSafetyVerdict.judge(request(AlertLevel.CRITICAL, 15.2, false));

        assertTrue(verdict.isPresent());
        assertEquals(InflightSafetyVerdict.Verdict.TERMINATE, verdict.get().verdict());
        assertEquals("在飞安全复核-终止", verdict.get().verdict().getOpType());
        assertTrue(verdict.get().riskMarked());
    }

    @Test
    void judge_shouldContinueWhenClearedEvenAtCriticalLevel() {
        // 解除标志排在级别之前命中：告警已清除时不得因级别残留而判终止
        Optional<InflightSafetyVerdict> verdict =
                InflightSafetyVerdict.judge(request(AlertLevel.CRITICAL, 15.2, true));

        assertTrue(verdict.isPresent());
        assertEquals(InflightSafetyVerdict.Verdict.CONTINUE, verdict.get().verdict());
        assertEquals("在飞安全复核-继续", verdict.get().verdict().getOpType());
        assertFalse(verdict.get().riskMarked(), "继续档不构成在飞风险");
    }

    @Test
    void judge_shouldReturnEmptyOnNaN() {
        assertTrue(InflightSafetyVerdict.judge(request(AlertLevel.WARN, Double.NaN, false)).isEmpty(),
                "NaN 不得兜成继续：「没判」不能伪装成「判过且安全」");
    }

    @Test
    void judge_shouldReturnEmptyOnInfiniteWind() {
        assertTrue(InflightSafetyVerdict.judge(
                request(AlertLevel.WARN, Double.POSITIVE_INFINITY, false)).isEmpty());
        assertTrue(InflightSafetyVerdict.judge(
                request(AlertLevel.WARN, Double.NEGATIVE_INFINITY, false)).isEmpty());
    }

    @Test
    void judge_shouldReturnEmptyOnNegativeWind() {
        assertTrue(InflightSafetyVerdict.judge(request(AlertLevel.WARN, -0.1, false)).isEmpty());
    }

    @Test
    void judge_shouldAcceptZeroWind() {
        // 零风速是合法观测（静风），属「有限值且不小于零」
        assertTrue(InflightSafetyVerdict.judge(request(AlertLevel.WARN, 0.0, false)).isPresent());
    }

    @Test
    void judge_shouldSkipInfoLevel() {
        assertTrue(InflightSafetyVerdict.judge(request(AlertLevel.INFO, 9.5, false)).isEmpty(),
                "Info 是提示级，不属气象超限两档");
    }

    @Test
    void judge_shouldSkipNullLevel() {
        assertTrue(InflightSafetyVerdict.judge(request(null, 9.5, false)).isEmpty());
    }

    @Test
    void judge_shouldReturnEmptyOnInvalidObservedAt() {
        InflightSafetyRequest invalid = new InflightSafetyRequest("EV-WIND-1",
                InflightSafetyTrigger.AIRSPACE_OR_WEATHER, "ORD-1", "UAV-1", 9.5,
                AlertLevel.WARN, 12, 0L, false);
        assertTrue(InflightSafetyVerdict.judge(invalid).isEmpty());
    }

    @Test
    void judge_shouldReturnEmptyOnNullRequest() {
        assertTrue(InflightSafetyVerdict.judge(null).isEmpty());
    }

    @Test
    void judge_shouldAllowNullOrderIdForDroneLookup() {
        InflightSafetyRequest noOrder = new InflightSafetyRequest("EV-WIND-1",
                InflightSafetyTrigger.AIRSPACE_OR_WEATHER, null, "UAV-1", 9.5,
                AlertLevel.WARN, 12, OBSERVED_AT, false);

        Optional<InflightSafetyVerdict> verdict = InflightSafetyVerdict.judge(noOrder);
        assertTrue(verdict.isPresent(), "告警不带单号时仍须出结论，单号由调用方按无人机反查");
        assertNull(verdict.get().orderId());
    }

    @Test
    void request_shouldClampNegativeDurationToZero() {
        InflightSafetyRequest negative = new InflightSafetyRequest("EV-WIND-1",
                InflightSafetyTrigger.AIRSPACE_OR_WEATHER, "ORD-1", "UAV-1", 9.5,
                AlertLevel.WARN, -5, OBSERVED_AT, false);
        assertEquals(0, negative.durationSec());
    }

    @Test
    void request_shouldRejectBlankEventId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new InflightSafetyRequest(" ", InflightSafetyTrigger.AIRSPACE_OR_WEATHER,
                        "ORD-1", "UAV-1", 9.5, AlertLevel.WARN, 12, OBSERVED_AT, false));
        assertTrue(ex.getMessage().contains("告警事件标识"));
    }

    @Test
    void request_shouldRejectNullTrigger() {
        assertThrows(IllegalArgumentException.class,
                () -> new InflightSafetyRequest("EV-WIND-1", null,
                        "ORD-1", "UAV-1", 9.5, AlertLevel.WARN, 12, OBSERVED_AT, false));
    }

    @Test
    void request_shouldRejectBlankDroneId() {
        assertThrows(IllegalArgumentException.class,
                () -> new InflightSafetyRequest("EV-WIND-1",
                        InflightSafetyTrigger.AIRSPACE_OR_WEATHER, "ORD-1", "  ", 9.5,
                        AlertLevel.WARN, 12, OBSERVED_AT, false));
    }
}
