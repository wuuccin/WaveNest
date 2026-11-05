package com.waterlogistics.dispatch.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WeatherIngestListener} 单测（M2 · 卢肄文 · 10-20 任务 B2）。
 *
 * <p>监听器本身很薄，但它承担一条纪律：<b>单条脏帧不得中断后续消息</b>——
 * 越界坐标／非法风速只记警告并丢弃（不入仓、不动闸门），绝不抛出异常把容器打停。
 * 这里直接调 {@code onWeatherMessage}，不需要 Spring 上下文与 RabbitMQ。
 */
class WeatherIngestListenerTest {

    private static final long T0 = 1_700_000_000_000L;

    private WeatherSnapshotStore store;
    private DispatchGateService gate;
    private WeatherIngestListener listener;

    @BeforeEach
    void setUp() {
        store = new WeatherSnapshotStore(WeatherSettings.defaults());
        gate = new DispatchGateService();
        WeatherGateTrigger trigger = new WeatherGateTrigger(store, gate, WeatherSettings.defaults());
        listener = new WeatherIngestListener(store, trigger);
    }

    private static WeatherData wind(double lon, double lat, double windMs, long obsTs) {
        return new WeatherData(lon, lat, windMs, 135.0, windMs + 3.0, obsTs, obsTs, obsTs);
    }

    @Test
    @DisplayName("合法风况：入仓，并驱动熔断判定")
    void validFrame_shouldStoreAndEvaluate() throws InterruptedException {
        long now = System.currentTimeMillis();
        listener.onWeatherMessage(wind(114.29, 30.59, 5.0, now));

        assertEquals(1, store.cellCount());
        assertTrue(store.isFresh(now));
        assertTrue(gate.isOpen(), "5 m/s 不熔断");

        // ★ 11-05（MED-PERF.11）：每帧评估改为「最小间隔 200 ms 节流」。
        // M1 改 3×3 后一节拍 9 条消息几乎同时到达，逐帧评估等于把 O(格数) 的
        // synchronized 评估乘 9；节流后一拍只评估一次，结论不受影响（评估读的是仓的全量）。
        // 本用例因此要跨过节流窗口，否则第二条消息的评估被跳过、断言 false 期望落空。
        Thread.sleep(WeatherIngestListener.MIN_EVALUATE_INTERVAL_MS + 50L);
        listener.onWeatherMessage(wind(114.30, 30.59, 15.0, now + 1_000L));
        assertEquals(2, store.cellCount());
        assertFalse(gate.isOpen(), "15 m/s 超严重阈值，应熔断");
        assertEquals(java.util.List.of(GateSource.AUTO), gate.summary().gateSources());
    }

    @Test
    @DisplayName("★ MED-PERF.11：同一节拍内九条风况只评估一次，但九格全入仓")
    void burstWithinOneTickShouldEvaluateOnce() {
        long now = System.currentTimeMillis();
        // 模拟 M1 一拍发 9 条（3×3），全部落在 200 ms 节流窗口内
        for (int ix = 0; ix < 3; ix++) {
            for (int iy = 0; iy < 3; iy++) {
                listener.onWeatherMessage(wind(114.29 + 0.03 * ix, 30.59 + 0.03 * iy, 5.0, now));
            }
        }
        assertEquals(9, store.cellCount(), "九条都必须入仓——节流只省评估，不丢数据");
        assertTrue(gate.isOpen(), "5 m/s 不熔断");

        // 最后一格里放一个超阈值：因为它的评估可能被节流跳过，闸门暂时未动；
        // 下一条（跨过窗口）到达时，评估读仓的全量，必然看到这个 15 m/s。
        listener.onWeatherMessage(wind(114.29, 30.59, 15.0, now + 1_000L));
        try {
            Thread.sleep(WeatherIngestListener.MIN_EVALUATE_INTERVAL_MS + 50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onWeatherMessage(wind(114.32, 30.59, 5.0, now + 2_000L));
        assertFalse(gate.isOpen(),
                "跨过节流窗口后的评估必须看到仓里那条 15 m/s——节流不得导致漏判");
    }

    @Test
    @DisplayName("★ 脏帧只丢不抛（单条异常不得中断后续消息）")
    void invalidFrame_shouldBeDroppedWithoutThrowing() {
        long now = System.currentTimeMillis();

        listener.onWeatherMessage(null);                              // 空帧
        listener.onWeatherMessage(wind(200.0, 30.0, 5.0, now));        // 经度越界
        listener.onWeatherMessage(wind(114.29, 30.59, Double.NaN, now)); // 风速 NaN
        listener.onWeatherMessage(wind(114.29, 30.59, 5.0, 0L));       // obsTs 非法

        assertEquals(0, store.cellCount(), "脏帧不得入仓");
        assertTrue(gate.isOpen(), "脏帧不得触发熔断");

        // 脏帧之后再来的合法帧照常处理
        listener.onWeatherMessage(wind(114.29, 30.59, 5.0, now));
        assertEquals(1, store.cellCount());
    }

    @Test
    @DisplayName("按位置多条：不同网格点各存一格，最不利取最大")
    void multiplePositions_shouldBeKeptSeparately() {
        long now = System.currentTimeMillis();
        listener.onWeatherMessage(wind(114.29, 30.59, 3.0, now));
        listener.onWeatherMessage(wind(114.30, 30.59, 4.5, now));
        listener.onWeatherMessage(wind(114.29, 30.60, 6.0, now));
        listener.onWeatherMessage(wind(114.30, 30.60, 2.5, now));

        assertEquals(4, store.cellCount(), "U-05 甲案：按位置发多条，逐条分格存");
        assertEquals(6.0, store.worstWindMs(now).orElseThrow(), 1e-12);
    }
}
