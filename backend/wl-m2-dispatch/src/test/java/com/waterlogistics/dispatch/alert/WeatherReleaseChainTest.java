package com.waterlogistics.dispatch.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.dispatch.weather.WeatherGateTrigger;
import com.waterlogistics.dispatch.weather.WeatherSettings;
import com.waterlogistics.dispatch.weather.WeatherSnapshotStore;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 11-16 序号 3-4：<b>闸门解除侧既有链路验证</b>——「风况回落 → 防抖确认 → 闸门解除 → 边沿检测 → 唤醒积压」。
 *
 * <p>本项是<b>验证任务而非改代码任务</b>：1-8 已裁掉回落手段与等待窗口（清偏置回自产基准 5.0 m/s，
 * 窗口 25 s），按清单边界只能改 {@code sys_param}，<b>不得改 {@code WeatherGateTrigger}／
 * {@link WeatherClearWaker} 的解除条件代码、不得改熔断语义</b>。故本类只钉住三件事：
 * <ol>
 *   <li><b>链路可用</b>：熔断后必须连续 {@code weatherClearDebounce} 条回落才解除，解除后边沿检测
 *       才唤醒积压——中间少一环（比如解除不发、边沿不触发）本类就会红；</li>
 *   <li><b>边沿只触发一次</b>：闸门持续打开期间不重复扫描积压；</li>
 *   <li><b>时间预算</b>：解除耗时 = 防抖样本数 × 采样周期 ＋ 轮询间隔，须落在 1-8 写的 30 s 上限内。
 *       这是本类唯一带数字的断言：<b>若有人把 {@code weatherClearDebounce} 调大到超预算，
 *       它会在合并前变红</b>，而不是等到演示现场才超时。</li>
 * </ol>
 *
 * <p>不需要 Spring／DB：闸门是纯内存状态机，积压扫描用 mock 替身。
 */
class WeatherReleaseChainTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final double CRITICAL = 12.0;

    /** 1-8 裁定的解除等待窗口上限，s（「上限仍 30 s」）。 */
    private static final long RELEASE_WINDOW_CAP_SEC = 30L;

    /** 1-8 写的窗口余量，s（「规则＝解除样本数×采样周期＋15 s 余量」）。 */
    private static final long RELEASE_MARGIN_SEC = 15L;

    /** 仿真器气象采样周期，s（1-8：「五个连续样本≈5 s」即 1 s 一条）。 */
    private static final long SAMPLE_PERIOD_SEC = 1L;

    /** 与 {@code wavenest.mq.wake-poll-interval-sec} 缺省值一致，s。 */
    private static final long WAKE_POLL_INTERVAL_SEC = 5L;

    private WeatherSnapshotStore store;
    private DispatchGateService gate;
    private WeatherGateTrigger trigger;
    private RescheduleService reschedule;
    private OrderTaskMapper orderTaskMapper;
    private WeatherClearWaker waker;

    @BeforeEach
    void setUp() {
        store = new WeatherSnapshotStore(WeatherSettings.defaults());
        gate = new DispatchGateService();
        trigger = new WeatherGateTrigger(store, gate, WeatherSettings.defaults());
        reschedule = mock(RescheduleService.class);
        orderTaskMapper = mock(OrderTaskMapper.class);
        waker = new WeatherClearWaker(gate, reschedule, orderTaskMapper);
    }

    private static WeatherData wind(double windMs, long obsTs) {
        return new WeatherData(114.29, 30.59, windMs, 135.0, windMs + 3.0, obsTs, obsTs, obsTs);
    }

    private void feed(double windMs, long obsTs) {
        store.accept(wind(windMs, obsTs));
        trigger.evaluate(obsTs);
    }

    private void backlogOf(int count) {
        List<OrderTaskEntity> page = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            OrderTaskEntity row = new OrderTaskEntity();
            row.setOrderId("ORD-" + i);
            row.setStatus(com.waterlogistics.contract.state.OrderStatus.ACCEPTED_WAIT_WAKE.getValue());
            page.add(row);
        }
        when(orderTaskMapper.selectList(any())).thenReturn(page);
        when(reschedule.reschedule(any())).thenReturn(Optional.of("DISPATCHED"));
    }

    @Test
    @DisplayName("★ 3-4：熔断 → 连续 N 条回落解除 → 边沿唤醒积压，链路一次贯通")
    void releaseChain_shouldWakeBacklogOnEdge() {
        int debounce = WeatherSettings.defaults().clearDebounceCount();
        backlogOf(3);

        feed(CRITICAL + 1.0, T0);
        assertFalse(gate.isOpen(), "前置：先熔断");

        // 闸门仍熔断：轮询不得唤醒（否则「熔断中派单」会被这一环绕过）
        waker.tick();
        verify(reschedule, never()).reschedule(any());

        // 前 N-1 条回落不解除：少一条都不行
        for (int i = 1; i < debounce; i++) {
            feed(CRITICAL - 1.0, T0 + i * 1_000L);
            assertFalse(gate.isOpen(), "第 " + i + " 条回落就解除了，防抖没生效");
        }
        waker.tick();
        verify(reschedule, never()).reschedule(any());

        // 第 N 条回落 → 解除
        feed(CRITICAL - 1.0, T0 + debounce * 1_000L);
        assertTrue(gate.isOpen(), "连续 " + debounce + " 条回落后必须解除");
        assertFalse(trigger.isTrippedByWeather(), "本地标记要跟着复位，否则下次不再重新置位");

        // 解除边沿 → 逐单重入队
        waker.tick();
        verify(reschedule, times(3)).reschedule(any());
    }

    @Test
    @DisplayName("★ 3-4：闸门持续打开期间不重复扫描积压（边沿触发，不是电平触发）")
    void releaseChain_shouldWakeOnlyOnce() {
        feed(CRITICAL + 1.0, T0);
        backlogOf(1);
        for (int i = 1; i <= WeatherSettings.defaults().clearDebounceCount(); i++) {
            feed(CRITICAL - 1.0, T0 + i * 1_000L);
        }
        assertTrue(gate.isOpen());

        waker.tick();
        waker.tick();
        waker.tick();

        verify(reschedule, times(1)).reschedule(any());
        verify(orderTaskMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("★ 3-4：解除耗时预算落在 1-8 的 30 s 上限内，无需改 weatherClearDebounce")
    void releaseBudget_shouldStayWithinWindow() {
        int debounce = WeatherSettings.defaults().clearDebounceCount();
        long detectionSec = (long) debounce * SAMPLE_PERIOD_SEC;
        long worstSec = detectionSec + WAKE_POLL_INTERVAL_SEC + RELEASE_MARGIN_SEC;

        assertTrue(worstSec <= RELEASE_WINDOW_CAP_SEC,
                "解除侧最坏耗时 " + worstSec + " s（防抖 " + detectionSec + " s ＋ 轮询 "
                        + WAKE_POLL_INTERVAL_SEC + " s ＋ 余量 " + RELEASE_MARGIN_SEC + " s）"
                        + " 已超 1-8 裁定的 " + RELEASE_WINDOW_CAP_SEC
                        + " s 窗口：要么按 3-4 边界只改 sys_param 的 weatherClearDebounce，"
                        + "要么回到序号 1 重新裁定窗口——不得直接调大防抖后合入");

        // 1-8 原文的账：5 条样本≈5 s，加轮询与余量＝25 s，落在 30 s 内
        assertEquals(25L, worstSec,
                "缺省的解除侧最坏耗时与 1-8 写的 25 s 窗口必须一致；不一致说明有一侧口径变了");
    }

    @Test
    @DisplayName("★ 3-4：人工冻结在场时不因气象解除而开闸（人力冻结不覆盖自动熔断）")
    void releaseChain_manualFreeze_shouldKeepGateClosed() {
        gate.trip(GateSource.MANUAL);
        feed(CRITICAL + 1.0, T0);
        for (int i = 1; i <= WeatherSettings.defaults().clearDebounceCount(); i++) {
            feed(CRITICAL - 1.0, T0 + i * 1_000L);
        }

        assertFalse(gate.isOpen(), "气象解除只清 AUTO，人工冻结仍在时闸门必须保持熔断");
        waker.tick();
        verify(reschedule, never()).reschedule(any());
    }
}
