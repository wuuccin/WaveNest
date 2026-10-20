package com.waterlogistics.dispatch.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WeatherGateTrigger} 单测（M2 · 卢肄文 · 10-20 任务 B2）。
 *
 * <p>覆盖三条保守性纪律：<b>超阈置 {@code AUTO} 熔断</b>、<b>连续 N 条回落才解除</b>、
 * <b>无新鲜风况时不动闸门</b>；并验证与人工冻结共存时不会把
 * {@code MANUAL} 一起清掉（{@code 22} §3.2「人力冻结不覆盖自动熔断」）。
 *
 * <p>本测试不需要 Spring／MQ／DB：{@link DispatchGateService} 与
 * {@link WeatherSnapshotStore} 都是无外部依赖的普通对象。
 */
class WeatherGateTriggerTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final double CRITICAL = 12.0;

    private WeatherSnapshotStore store;
    private DispatchGateService gate;
    private WeatherGateTrigger trigger;

    @BeforeEach
    void setUp() {
        store = new WeatherSnapshotStore(WeatherSettings.defaults());
        gate = new DispatchGateService();
        trigger = new WeatherGateTrigger(store, gate, WeatherSettings.defaults());
    }

    private static WeatherData wind(double windMs, long obsTs) {
        return new WeatherData(114.29, 30.59, windMs, 135.0, windMs + 3.0, obsTs, obsTs, obsTs);
    }

    private void feed(double windMs, long obsTs) {
        store.accept(wind(windMs, obsTs));
        trigger.evaluate(obsTs);
    }

    @Test
    @DisplayName("风速超严重阈值 → 置 GateSource.AUTO 熔断，isOpen 变 false")
    void exceedingCritical_shouldTripAuto() {
        assertTrue(gate.isOpen());

        feed(CRITICAL + 0.1, T0);

        assertFalse(gate.isOpen(), "超阈值必须熔断");
        assertEquals(GateStatus.BROKEN, gate.summary().gateStatus());
        assertEquals(java.util.List.of(GateSource.AUTO), gate.summary().gateSources());
        assertTrue(trigger.isTrippedByWeather());
    }

    @Test
    @DisplayName("未超阈值不熔断；恰好等于阈值也不熔断（判据是「超过」）")
    void notExceeding_shouldStayOpen() {
        feed(CRITICAL - 0.1, T0);
        assertTrue(gate.isOpen());

        feed(CRITICAL, T0 + 1_000L);
        assertTrue(gate.isOpen(), "等于阈值不算超过");
        assertFalse(trigger.isTrippedByWeather());
    }

    @Test
    @DisplayName("熔断后要连续 weatherClearDebounce 条回落才解除，单条不够")
    void clearing_shouldRequireDebounce() {
        feed(CRITICAL + 1.0, T0);
        assertFalse(gate.isOpen());

        int debounce = WeatherSettings.defaults().clearDebounceCount();   // 5
        for (int i = 1; i < debounce; i++) {
            feed(5.0, T0 + i * 1_000L);
            assertFalse(gate.isOpen(), "第 " + i + " 条回落不应解除熔断");
        }
        assertEquals(debounce - 1, trigger.consecutiveBelow());

        feed(5.0, T0 + debounce * 1_000L);
        assertTrue(gate.isOpen(), "连续 " + debounce + " 条回落应解除");
        assertFalse(trigger.isTrippedByWeather());
        assertEquals(0, trigger.consecutiveBelow());
    }

    @Test
    @DisplayName("抖动不解除：连续计数中途被超阈打断后要重新数")
    void jitter_shouldResetCounter() {
        feed(CRITICAL + 1.0, T0);
        assertFalse(gate.isOpen());

        feed(5.0, T0 + 1_000L);
        feed(5.0, T0 + 2_000L);
        feed(5.0, T0 + 3_000L);
        assertEquals(3, trigger.consecutiveBelow());

        feed(CRITICAL + 2.0, T0 + 4_000L);          // 又超阈
        assertEquals(0, trigger.consecutiveBelow(), "再次超阈必须清零");
        assertFalse(gate.isOpen());

        feed(5.0, T0 + 5_000L);
        assertEquals(1, trigger.consecutiveBelow(), "重新从 1 开始数");
        assertFalse(gate.isOpen());
    }

    @Test
    @DisplayName("★ 无新鲜风况时不置位也不解除（缺数据不等于安全）")
    void noFreshData_shouldNotTouchGate() {
        long now = T0;
        assertFalse(trigger.evaluate(now), "空仓无动作");
        assertTrue(gate.isOpen());

        // 熔断后风况过期：不得因为「没有数据」就自动解除
        feed(CRITICAL + 1.0, T0);
        assertFalse(gate.isOpen());
        long expired = T0 + WeatherSettings.defaults().ttlMs() + 1L;
        for (int i = 0; i < 10; i++) {
            assertFalse(trigger.evaluate(expired + i), "过期风况不得解除熔断");
        }
        assertFalse(gate.isOpen(), "熔断保持（保守方向）");
        assertTrue(trigger.isTrippedByWeather());
    }

    @Test
    @DisplayName("人工冻结不被气象解除掉：MANUAL + AUTO 共存时只移除 AUTO")
    void manualFreeze_shouldSurviveWeatherClear() {
        gate.trip(GateSource.MANUAL);
        assertFalse(gate.isOpen());

        feed(CRITICAL + 1.0, T0);                     // 自动熔断也加上
        assertEquals(2, gate.summary().gateSources().size());

        int debounce = WeatherSettings.defaults().clearDebounceCount();
        for (int i = 1; i <= debounce; i++) {
            feed(5.0, T0 + i * 1_000L);
        }
        assertFalse(gate.isOpen(), "人工冻结仍在，闸门不得恢复");
        assertEquals(java.util.List.of(GateSource.MANUAL), gate.summary().gateSources(),
                "只应移除 AUTO，人工冻结必须保留");
    }

    @Test
    @DisplayName("重复超阈不重复置位：evaluate 幂等，第二次返回 false")
    void repeatedExceeding_shouldBeIdempotent() {
        feed(CRITICAL + 1.0, T0);
        assertTrue(gate.summary().gateSources().size() == 1);

        store.accept(wind(CRITICAL + 2.0, T0 + 1_000L));
        assertFalse(trigger.evaluate(T0 + 1_000L), "已在熔断状态，不应再报告状态变化");
        assertEquals(1, gate.summary().gateSources().size(), "来源不得重复登记");
    }
}
