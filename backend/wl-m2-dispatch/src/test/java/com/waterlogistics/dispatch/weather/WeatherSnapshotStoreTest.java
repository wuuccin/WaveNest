package com.waterlogistics.dispatch.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.WeatherData;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WeatherSnapshotStore} 单测（M2 · 卢肄文 · 10-20 任务 B2／B5 消费侧）。
 *
 * <p>覆盖：<b>按网格点分格存储</b>（U-05 甲案「按位置发多条」的落地）、同格覆盖、
 * <b>最不利风取所有格的最大值</b>、TTL 过期不参与取用、
 * <b>无新鲜数据时返回空而不是 0</b>（危险方向不能给默认值）、最近点查询、脏帧拒收。
 */
class WeatherSnapshotStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    /** 赣江演示网格：2×2 点，间隔 0.01 度（≈1.1 km），与 M1 仿真器同口径。 */
    private static final double LON0 = 114.29;
    private static final double LAT0 = 30.59;
    private static final double STEP = 0.01;

    private static WeatherSnapshotStore store() {
        // TTL 120 s、严重阈值 12 m/s、防抖 5 条
        return new WeatherSnapshotStore(WeatherSettings.defaults());
    }

    private static WeatherData wind(double lon, double lat, double windMs, long obsTs) {
        return new WeatherData(lon, lat, windMs, 135.0, windMs + 3.0, obsTs, obsTs, obsTs);
    }

    /** 灌一整轮 2×2 网格。 */
    private static void fillGrid(WeatherSnapshotStore store, double fastestMs, long obsTs) {
        double[] speeds = {fastestMs, 4.0, 3.0, 2.0};
        int index = 0;
        for (int ix = 0; ix < 2; ix++) {
            for (int iy = 0; iy < 2; iy++) {
                store.accept(wind(LON0 + STEP * ix, LAT0 + STEP * iy, speeds[index++], obsTs));
            }
        }
    }

    @Test
    @DisplayName("按网格点分格存：2×2 = 4 格，同格重复发只覆盖不新增")
    void gridCells_shouldDeduplicateByPosition() {
        WeatherSnapshotStore store = store();
        fillGrid(store, 5.0, T0);
        assertEquals(4, store.cellCount(), "2×2 网格应存 4 格");

        // 同位置再发一条更新的风况 → 格数不变、值被覆盖
        store.accept(wind(LON0, LAT0, 9.0, T0 + 1_000L));
        assertEquals(4, store.cellCount());
        assertEquals(9.0, store.nearest(LON0, LAT0, T0 + 1_000L).orElseThrow().windSpeedMs(), 1e-12);
    }

    @Test
    @DisplayName("最不利风取所有格的【最大值】，不是最后一条、也不是平均")
    void worstWind_shouldTakeMaximumAcrossCells() {
        WeatherSnapshotStore store = store();
        fillGrid(store, 11.5, T0);

        assertEquals(11.5, store.worstWindMs(T0).orElseThrow(), 1e-12);

        // 后发一条很小的风，最不利值必须仍是 11.5（不能变成「最后一条」）
        store.accept(wind(LON0 + STEP, LAT0 + STEP, 1.0, T0 + 1_000L));
        assertEquals(11.5, store.worstWindMs(T0 + 1_000L).orElseThrow(), 1e-12,
                "最不利风应按位置取最大，不能退化成最后一条");
    }

    @Test
    @DisplayName("★ 无新鲜数据时最不利风返回【空】而不是 0（0 会被下游当成无风可飞）")
    void noFreshData_shouldReturnEmptyNotZero() {
        WeatherSnapshotStore store = store();
        assertTrue(store.worstWindMs(T0).isEmpty(), "空仓必须返回空");
        assertFalse(store.isFresh(T0));

        fillGrid(store, 5.0, T0);
        long expired = T0 + WeatherSettings.defaults().ttlMs() + 1L;
        assertTrue(store.worstWindMs(expired).isEmpty(), "超过 TTL 的风况不得参与取用");
        assertFalse(store.isFresh(expired));
        // 但原始数据还在（只是不新鲜），便于诊断
        assertEquals(4, store.cellCount());
        assertTrue(store.newest().isPresent());
    }

    @Test
    @DisplayName("TTL 边界：正好等于 ttl 仍算新鲜，多 1 ms 就不算")
    void ttlBoundary_shouldBeInclusive() {
        WeatherSnapshotStore store = store();
        fillGrid(store, 5.0, T0);
        long ttl = WeatherSettings.defaults().ttlMs();

        assertTrue(store.worstWindMs(T0 + ttl).isPresent(), "恰好等于 ttl 仍新鲜");
        assertTrue(store.worstWindMs(T0 + ttl + 1L).isEmpty(), "超出 1 ms 即过期");
    }

    @Test
    @DisplayName("最近的格：按平面距离取，且只在新鲜集合里挑")
    void nearest_shouldPickClosestFreshCell() {
        WeatherSnapshotStore store = store();
        // 西北角 3.0，东南角 7.0
        store.accept(wind(LON0, LAT0, 3.0, T0));
        store.accept(wind(LON0 + STEP, LAT0 + STEP, 7.0, T0));

        assertEquals(3.0, store.nearest(LON0 + 0.0005, LAT0 + 0.0005, T0)
                .orElseThrow().windSpeedMs(), 1e-12, "应取西北角");
        assertEquals(7.0, store.nearest(LON0 + STEP - 0.0005, LAT0 + STEP - 0.0005, T0)
                .orElseThrow().windSpeedMs(), 1e-12, "应取东南角");

        // 西北角过期后，最近点只能是东南角
        store.accept(wind(LON0 + STEP, LAT0 + STEP, 7.0, T0 + 200_000L));
        Optional<WeatherData> after = store.nearest(LON0, LAT0, T0 + 200_000L);
        assertTrue(after.isPresent());
        assertEquals(7.0, after.get().windSpeedMs(), 1e-12, "过期格不得被选中");
    }

    @Test
    @DisplayName("最新的观测时刻：取所有格的最大 obsTs；空仓为 0")
    void lastObsTs_shouldBeMaxOfCells() {
        WeatherSnapshotStore store = store();
        assertEquals(0L, store.lastObsTsMs());
        store.accept(wind(LON0, LAT0, 3.0, T0));
        store.accept(wind(LON0 + STEP, LAT0, 4.0, T0 + 5_000L));
        assertEquals(T0 + 5_000L, store.lastObsTsMs());
    }

    @Test
    @DisplayName("脏帧拒收：坐标越界、风速为负、观测时刻为 0 都要抛（由监听器记警告后丢弃）")
    void invalidFrame_shouldBeRejected() {
        WeatherSnapshotStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.accept(null));
        assertThrows(IllegalArgumentException.class, () -> store.accept(wind(200.0, 30.0, 5.0, T0)));
        assertThrows(IllegalArgumentException.class, () -> store.accept(wind(114.0, 95.0, 5.0, T0)));
        assertThrows(IllegalArgumentException.class, () -> store.accept(wind(LON0, LAT0, -1.0, T0)));
        assertThrows(IllegalArgumentException.class, () -> store.accept(wind(LON0, LAT0, 5.0, 0L)));
        assertEquals(0, store.cellCount(), "脏帧不得入库");
    }

    @Test
    @DisplayName("clear 之后回到空仓状态")
    void clear_shouldEmptyStore() {
        WeatherSnapshotStore store = store();
        fillGrid(store, 5.0, T0);
        store.clear();
        assertEquals(0, store.cellCount());
        assertTrue(store.worstWindMs(T0).isEmpty());
    }

    @Test
    @DisplayName("worstWindMsOrDefault：有风况取最不利，无风况返回调用方兜底值（不替调用方选 0）")
    void worstWindOrDefault_shouldUseCallerFallback() {
        WeatherSnapshotStore store = store();
        assertEquals(0.0, store.worstWindMsOrDefault(T0, 0.0), 1e-12);
        assertEquals(7.5, store.worstWindMsOrDefault(T0, 7.5), 1e-12,
                "无新鲜风况时应返回调用方给的兜底值");

        fillGrid(store, 9.0, T0);
        assertEquals(9.0, store.worstWindMsOrDefault(T0, 7.5), 1e-12,
                "有风况时兜底值不参与");
    }

    @Test
    @DisplayName("参数校验：TTL 必为正、兜底风速不得为 0、严重阈值须高于预警、防抖至少 1 条")
    void settingsValidation_shouldRejectBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(0L, 8.0, 12.0, 5));
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(1_000L, 0.0, 12.0, 5),
                "兜底风速不得为 0（0 会被当成「无风、可飞」）");
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(1_000L, 8.0, 0.0, 5));
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(1_000L, 12.0, 8.0, 5),
                "严重阈值必须高于预警阈值");
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(1_000L, 8.0, 12.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WeatherSnapshotStore(null));

        // 已登记值组合（预警 8.0 < 严重 12.0）必须能构造
        assertEquals(8.0, WeatherSettings.defaults().warningWindMs(), 1e-12);
        assertEquals(12.0, WeatherSettings.defaults().criticalWindMs(), 1e-12);
    }

    @Test
    @DisplayName("平面距离近似：0.01 度纬度约 1113 m")
    void planadDistance_shouldBeAbout1113mPerHundredthDegree() {
        double distance = WeatherSnapshotStore.planarDistanceM(LON0, LAT0, LON0, LAT0 + 0.01);
        assertEquals(1113.2, distance, 1.0);
    }
}
