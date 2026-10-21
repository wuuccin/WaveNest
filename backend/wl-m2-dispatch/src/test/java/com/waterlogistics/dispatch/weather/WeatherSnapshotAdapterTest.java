package com.waterlogistics.dispatch.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.contract.mq.WeatherData;
import org.junit.jupiter.api.Test;

/**
 * 会面风况适配器单测：按位置取最近的新鲜观测，过期或缺失时回落空。
 */
class WeatherSnapshotAdapterTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final double LON = 121.0;
    private static final double LAT = 30.0;

    @Test
    void latest_shouldReturnNearestFreshWind() {
        WeatherSnapshotStore store = new WeatherSnapshotStore(WeatherSettings.defaults());
        store.accept(weather(LON + 0.02, LAT, 4.0, 95.0, NOW));
        store.accept(weather(LON, LAT, 5.5, 355.0, NOW));
        WeatherSnapshotAdapter adapter = new WeatherSnapshotAdapter(store);

        WeatherSnapshotPort.LatestWind wind = adapter.latest(LON, LAT, NOW);

        assertEquals(5.5, wind.speedMs(), 1e-9, "应取最近的格点风况");
        assertEquals(355.0, wind.dirDeg(), 1e-9);
        assertEquals(NOW, wind.obsMs());
    }

    @Test
    void latest_shouldNormalizeDirection() {
        WeatherSnapshotStore store = new WeatherSnapshotStore(WeatherSettings.defaults());
        store.accept(weather(LON, LAT, 3.0, -5.0, NOW));
        WeatherSnapshotAdapter adapter = new WeatherSnapshotAdapter(store);

        assertEquals(355.0, adapter.latest(LON, LAT, NOW).dirDeg(), 1e-9, "风向须回卷到 [0,360)");
    }

    @Test
    void latest_shouldReturnNullWhenStaleOrMissing() {
        WeatherSnapshotStore empty = new WeatherSnapshotStore(WeatherSettings.defaults());
        assertNull(new WeatherSnapshotAdapter(empty).latest(LON, LAT, NOW), "无数据应回落");

        WeatherSnapshotStore stale = new WeatherSnapshotStore(WeatherSettings.defaults());
        stale.accept(weather(LON, LAT, 3.0, 90.0, NOW - WeatherSettings.defaults().ttlMs() - 1L));
        assertNull(new WeatherSnapshotAdapter(stale).latest(LON, LAT, NOW), "过期风况须视为无数据");
    }

    private static WeatherData weather(double lon, double lat, double speedMs, double dirDeg, long obsTs) {
        return new WeatherData(lon, lat, speedMs, dirDeg, 0.0, obsTs, obsTs, obsTs);
    }
}
