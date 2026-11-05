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
    void validFrame_shouldStoreAndEvaluate() {
        long now = System.currentTimeMillis();
        listener.onWeatherMessage(wind(114.29, 30.59, 5.0, now));

        assertEquals(1, store.cellCount());
        assertTrue(store.isFresh(now));
        assertTrue(gate.isOpen(), "5 m/s 不熔断");

        listener.onWeatherMessage(wind(114.30, 30.59, 15.0, now + 1_000L));
        assertEquals(2, store.cellCount());
        assertFalse(gate.isOpen(), "15 m/s 超严重阈值，应熔断");
        assertEquals(java.util.List.of(GateSource.AUTO), gate.summary().gateSources());
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
