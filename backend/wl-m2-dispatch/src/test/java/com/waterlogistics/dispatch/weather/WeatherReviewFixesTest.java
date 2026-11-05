package com.waterlogistics.dispatch.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 11-05 气象评审 17 条的关闭依据（逐条对应，PR 描述里引这里的用例名）。
 *
 * <p>每个用例的 {@code @DisplayName} 前缀就是评审编号，便于「编号 → 依据」一对一核对：
 * <ul>
 *   <li>{@code HIGH.37} —— 闸门评估抛异常不得从监听器冒出去；</li>
 *   <li>{@code HIGH.38} —— 适配器取数失败返回 null 而非抛出；</li>
 *   <li>{@code HIGH.39} —— 乱序旧帧不得覆盖新帧；</li>
 *   <li>{@code MED-BUG.66} —— 非正 TTL 不再静默替换缺省值；</li>
 *   <li>{@code MED-BUG.69} —— TTL 与防抖有上界；</li>
 *   <li>{@code MED-BUG.70} —— 未来 obsTs 的观测不得视为新鲜；</li>
 *   <li>{@code MED-BUG.71} —— {@code LatestWind} 的不变式；</li>
 *   <li>{@code MED-BUG.72} —— {@code isFresh} 短路，不物化整表；</li>
 *   <li>{@code MED-BUG.73} —— 兜底风速 NaN／负值被拒；</li>
 *   <li>{@code MED-BUG.74} —— {@code nearest} 有最大半径；</li>
 *   <li>{@code MED-PERF.11} —— 每帧评估改为节流（用例在 {@code WeatherIngestListenerTest}）；</li>
 *   <li>{@code MED-PERF.12} —— 网格 TTL 淘汰与容量上限；</li>
 *   <li>{@code LOW-MAINT.30} —— 缺省值只在一处定义；</li>
 *   <li>{@code LOW-MAINT.31} —— 适配器不再从被适配对象反查参数；</li>
 *   <li>{@code LOW-MAINT.32} —— 端口是函数式接口。</li>
 * </ul>
 */
class WeatherReviewFixesTest {

    private static final long T0 = 1_700_000_000_000L;

    private WeatherSnapshotStore store;
    private DispatchGateService gate;
    private WeatherGateTrigger trigger;
    private WeatherIngestListener listener;

    @BeforeEach
    void setUp() {
        store = new WeatherSnapshotStore(WeatherSettings.defaults());
        gate = new DispatchGateService();
        trigger = new WeatherGateTrigger(store, gate, WeatherSettings.defaults());
        listener = new WeatherIngestListener(store, trigger);
    }

    private static WeatherData wind(double lon, double lat, double windMs, long obsTs) {
        return new WeatherData(lon, lat, windMs, 135.0, windMs + 3.0, obsTs, obsTs, obsTs);
    }

    // ───────── HIGH.37 ─────────

    @Test
    @DisplayName("HIGH.37：闸门评估抛异常时监听器不外抛（否则消息被判失败、重投）")
    void high37_evaluateFailureMustNotEscapeListener() {
        WeatherGateTrigger failing = new WeatherGateTrigger(store, gate, WeatherSettings.defaults()) {
            @Override
            public synchronized boolean evaluate(long nowMs) {
                throw new IllegalStateException("闸门服务不可用（模拟）");
            }
        };
        WeatherIngestListener guarded = new WeatherIngestListener(store, failing);

        long now = System.currentTimeMillis();
        // 原实现会把 IllegalStateException 抛到 @RabbitListener 之外，消息被判处理失败
        guarded.onWeatherMessage(wind(114.29, 30.59, 5.0, now));

        assertEquals(1, store.cellCount(), "评估失败不影响入仓——入仓在评估之前且各自 try");
    }

    // ───────── HIGH.38 ─────────

    @Test
    @DisplayName("HIGH.38：适配器取数抛运行时异常时返回 null（气象是可选输入，不放大成调度失败）")
    void high38_adapterSwallowsStoreFailure() {
        WeatherSnapshotStore exploding = new WeatherSnapshotStore(WeatherSettings.defaults()) {
            @Override
            public java.util.Optional<WeatherData> nearest(double lon, double lat, long nowMs) {
                throw new IllegalStateException("快照仓内部错误（模拟）");
            }
        };
        assertNull(new WeatherSnapshotAdapter(exploding).latest(114.29, 30.59, T0),
                "取数失败必须降级为「无风况」，由调用方回落静态风况");
    }

    /** 与上面同一断言，抽成方法便于复用 {@code assertNull} 的静态导入。 */
    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    @Test
    @DisplayName("HIGH.38 补充：入参越界同样返回 null，不抛")
    void high38_adapterRejectsIllegalArgumentsSilently() {
        WeatherSnapshotAdapter adapter = new WeatherSnapshotAdapter(store);
        assertNull(adapter.latest(200.0, 30.0, T0), "经度越界");
        assertNull(adapter.latest(114.0, 95.0, T0), "纬度越界");
        assertNull(adapter.latest(Double.NaN, 30.0, T0), "经度 NaN");
    }

    // ───────── HIGH.39 ─────────

    @Test
    @DisplayName("HIGH.39：乱序到达的旧帧不得覆盖新帧（风况不得倒退）")
    void high39_lateFrameMustNotOverwriteNewerOne() {
        store.accept(wind(114.29, 30.59, 9.0, T0 + 5_000L));
        store.accept(wind(114.29, 30.59, 3.0, T0));   // 旧帧后到

        assertEquals(9.0, store.nearest(114.29, 30.59, T0 + 5_000L).orElseThrow().windSpeedMs(), 1e-12,
                "旧帧到达后该格仍是 9.0——不能被 3.0 覆盖");
        assertEquals(1L, store.staleFrameCount(), "应记一次陈旧帧丢弃");
    }

    @Test
    @DisplayName("HIGH.39 补充：同一 obsTs 的重投允许覆盖（幂等），不算陈旧帧")
    void high39_sameObsTsIsIdempotentNotStale() {
        store.accept(wind(114.29, 30.59, 9.0, T0));
        store.accept(wind(114.29, 30.59, 9.0, T0));
        assertEquals(0L, store.staleFrameCount(), "同一时刻重投是幂等的，不该计入丢弃");
    }

    // ───────── MED-BUG.66 ─────────

    @Test
    @DisplayName("MED-BUG.66：非正 TTL 抛异常，不再静默替换成缺省值")
    void medBug66_nonPositiveTtlThrows() {
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(0L, 8.0, 12.0, 5));
        assertThrows(IllegalArgumentException.class, () -> new WeatherSettings(-1_000L, 8.0, 12.0, 5));
    }

    @Test
    @DisplayName("MED-BUG.66 补充：WeatherConfig 装配时不吞配置错误（把异常交给 Spring 启动期暴露）")
    void medBug66_configPropagatesBadTtl() {
        WeatherConfig config = new WeatherConfig();
        com.waterlogistics.storage.param.SysParamLoader loader =
                new com.waterlogistics.storage.param.SysParamLoader(null) {
                    @Override
                    public double getDouble(String key, double defaultValue) {
                        return defaultValue;
                    }

                    @Override
                    public int getInt(String key, int defaultValue) {
                        return defaultValue;
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> config.weatherSettings(loader, 0L),
                "配置写 0 时必须启动失败，而不是悄悄用 120 s");
        assertEquals(60_000L, config.weatherSettings(loader, 60_000L).ttlMs(),
                "合法 TTL 原样生效，不再被替换");
    }

    // ───────── MED-BUG.69 ─────────

    @Test
    @DisplayName("MED-BUG.69：TTL 与防抖都有上界，越界即抛（防过期风况长期生效 / 熔断永不解除）")
    void medBug69_upperBoundsEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSettings(WeatherSettings.MAX_TTL_MS + 1L, 8.0, 12.0, 5),
                "TTL 超上界应拒绝");
        new WeatherSettings(WeatherSettings.MAX_TTL_MS, 8.0, 12.0, 5);   // 边界值可构造

        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSettings(1_000L, 8.0, 12.0, WeatherSettings.MAX_CLEAR_DEBOUNCE + 1),
                "防抖超上界应拒绝");
        new WeatherSettings(1_000L, 8.0, 12.0, WeatherSettings.MAX_CLEAR_DEBOUNCE);
    }

    // ───────── MED-BUG.70 ─────────

    @Test
    @DisplayName("MED-BUG.70：超前超过容差的 obsTs 不视为新鲜（否则会长期压住正常值）")
    void medBug70_futureObservationRejected() {
        long now = 1_700_000_000_000L;
        store.accept(wind(114.29, 30.59, 3.0, now));
        // 一条「明天」的风况：now − obsTs 恒为负，永远 ≤ TTL
        store.accept(wind(114.30, 30.59, 20.0, now + 86_400_000L));

        WeatherSnapshotAdapter adapter = new WeatherSnapshotAdapter(store);
        WeatherSnapshotPort.LatestWind wind = adapter.latest(114.30, 30.59, now);
        // 最近点会挑到那条未来帧，但适配器必须把它判成不可用
        assertNull(wind, "未来观测必须被拒，返回 null 让调用方回落");
    }

    @Test
    @DisplayName("MED-BUG.70 补充：容差内的轻微超前仍放行（吸收时钟抖动）")
    void medBug70_smallFutureSkewAccepted() {
        long now = 1_700_000_000_000L;
        store.accept(wind(114.29, 30.59, 4.0, now + 5_000L));
        WeatherSnapshotPort.LatestWind wind =
                new WeatherSnapshotAdapter(store).latest(114.29, 30.59, now);
        assertEquals(4.0, wind.speedMs(), 1e-12, "5 s 超前在 30 s 容差内，应正常放行");
    }

    // ───────── MED-BUG.71 ─────────

    @Test
    @DisplayName("MED-BUG.71：LatestWind 构造期校验风速为正、风向有限、观测时刻为正，并把风向归一")
    void medBug71_latestWindInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshotPort.LatestWind(-1.0, 90.0, T0), "负风速应拒");
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshotPort.LatestWind(0.0, 90.0, T0), "零风速应拒（静风不该当有效观测）");
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshotPort.LatestWind(Double.NaN, 90.0, T0), "NaN 风速应拒");
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshotPort.LatestWind(5.0, Double.NaN, T0), "NaN 风向应拒");
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshotPort.LatestWind(5.0, 90.0, 0L), "非正 obsTs 应拒");

        assertEquals(355.0, new WeatherSnapshotPort.LatestWind(5.0, -5.0, T0).dirDeg(), 1e-12,
                "风向应归一进 [0,360)");
        assertEquals(10.0, new WeatherSnapshotPort.LatestWind(5.0, 370.0, T0).dirDeg(), 1e-12);
    }

    // ───────── MED-BUG.72 ─────────

    @Test
    @DisplayName("MED-BUG.72：isFresh 短路遍历，不物化整表（用「一格新鲜」的场景验证结论正确）")
    void medBug72_isFreshShortCircuits() {
        store.accept(wind(114.29, 30.59, 3.0, T0));
        // 再灌 200 个过期格：若实现是 fresh(now).isEmpty()，这里会分配一个 200 元素的空表
        for (int i = 0; i < 200; i++) {
            store.accept(wind(114.29 + 0.001 * i, 30.59, 3.0, T0 - 10_000_000L));
        }
        store.accept(wind(114.31, 30.59, 3.0, T0));
        assertTrue(store.isFresh(T0), "有一格新鲜即为真");
        assertFalse(store.isFresh(T0 + WeatherSettings.defaults().ttlMs() + 1L), "全部过期即为假");
    }

    // ───────── MED-BUG.73 ─────────

    @Test
    @DisplayName("MED-BUG.73：兜底风速 NaN／负值被拒（NaN 会让风阻比较恒为 false，等于放行一切）")
    void medBug73_fallbackValidated() {
        assertThrows(IllegalArgumentException.class, () -> store.worstWindMsOrDefault(T0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> store.worstWindMsOrDefault(T0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> store.worstWindMsOrDefault(T0, -1.0));
        assertEquals(0.0, store.worstWindMsOrDefault(T0, 0.0), 1e-12, "0 是合法兜底值");
    }

    // ───────── MED-BUG.74 ─────────

    @Test
    @DisplayName("MED-BUG.74：nearest 超出最大半径返回空（几十公里外的格点风毫无意义）")
    void medBug74_nearestHasMaxRadius() {
        store.accept(wind(115.840, 28.640, 5.0, T0));

        assertTrue(store.nearest(115.840, 28.640, T0).isPresent(), "同点必有结果");
        assertTrue(store.nearest(115.900, 28.700, T0).isPresent(), "约 4.5 km 在 20 km 内");

        // 约 111 km 外（1 度纬度）
        assertTrue(store.nearest(115.840, 29.640, T0).isEmpty(),
                "超出 20 km 半径应返回空，而不是给一个不相关的格点风");
        // 显式可调半径：给 200 km 就放行
        assertTrue(store.nearest(115.840, 29.640, T0, 200.0).isPresent(), "显式半径可放宽");
        assertThrows(IllegalArgumentException.class,
                () -> store.nearest(115.840, 28.640, T0, 0.0), "半径非正应拒");
    }

    // ───────── MED-PERF.12（裁定：TTL 3 倍 / 容量 4096） ─────────

    @Test
    @DisplayName("MED-PERF.12：容量上限生效，超限时淘汰最旧的格")
    void medPerf12_capacityLimitEnforced() {
        assertTrue(WeatherSnapshotStore.MAX_CELLS >= 1024,
                "容量上限应为 4096 量级（裁定值），实际 " + WeatherSnapshotStore.MAX_CELLS);
        // 灌满 + 超出：按 1e-3 度铺九行 × 每行 500 列 ≈ 4500 格（> 4096）
        int rows = 9;
        int perRow = WeatherSnapshotStore.MAX_CELLS / rows + 100;
        int target = rows * perRow;
        // obsTs 用「当前时刻前一秒 + 递增值」：若用固定的 T0（2023 年），
        // 过期扫描会把刚写进去的格也判成过期一起清掉，测不出容量上限这条。
        long base = System.currentTimeMillis() - 1_000L;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < perRow; c++) {
                // 经度 100.0 起每 0.001° 一格（perRow×0.001 ≈ 0.55°，不越界）
                double lon = 100.0 + c * 0.001;
                double lat = 20.0 + r * 0.01;
                store.accept(wind(lon, lat, 5.0, base + r * perRow + c));
            }
        }
        assertTrue(target > WeatherSnapshotStore.MAX_CELLS, "用例应真的超过容量上限，实际 " + target);

        assertEquals(WeatherSnapshotStore.MAX_CELLS, store.cellCount(),
                "格数不得超过容量上限");
        assertTrue(store.evictedCellCount() > 0, "应有淘汰记录");
        // 最旧的（r=0,c=0）应已被淘汰；最新写入的那格必须还在
        long now = base + target;
        assertTrue(store.nearest(100.0, 20.0, now, 1.0).isEmpty(), "最旧的格应被优先淘汰");
        assertTrue(store.nearest(100.0 + (perRow - 1) * 0.001, 20.0 + (rows - 1) * 0.01, now, 1.0).isPresent(),
                "最新写入的格必须保留");
    }

    @Test
    @DisplayName("MED-PERF.12：超 TTL×3 的旧格会被清掉，不再参与「最不利风」")
    void medPerf12_expiredCellsAreEvicted() {
        long old = T0 - WeatherSettings.defaults().ttlMs() * WeatherSnapshotStore.EVICT_TTL_FACTOR - 1L;
        store.accept(wind(114.29, 30.59, 99.0, old));   // 一条「很久以前」的大风
        // 触发扫描：每 EVICT_SCAN_INTERVAL 次写入扫一遍
        for (int i = 0; i < WeatherSnapshotStore.EVICT_SCAN_INTERVAL; i++) {
            store.accept(wind(114.20 + i * 0.01, 30.50, 5.0, T0));
        }
        assertTrue(store.evictedCellCount() >= 1, "过期格应被清掉");
        assertEquals(5.0, store.worstWindMs(T0).orElseThrow(), 1e-12,
                "被清掉的旧大风不得再出现在「最不利风」里");
    }

    // ───────── LOW-MAINT.30 ─────────

    @Test
    @DisplayName("LOW-MAINT.30：缺省值只在 WeatherSettings 一处定义，其余全引常量")
    void lowMaint30_defaultsLiveInOnePlace() {
        assertEquals(WeatherSettings.DEFAULT_TTL_MS, WeatherSettings.defaults().ttlMs());
        assertEquals(WeatherSettings.DEFAULT_WARNING_WIND_MS, WeatherSettings.defaults().warningWindMs(), 1e-12);
        assertEquals(WeatherSettings.DEFAULT_CRITICAL_WIND_MS, WeatherSettings.defaults().criticalWindMs(), 1e-12);
        assertEquals(WeatherSettings.DEFAULT_CLEAR_DEBOUNCE, WeatherSettings.defaults().clearDebounceCount());
        // WeatherConfig 转存的常量必须是同一个对象的值（改一处两边同步）
        assertEquals(WeatherSettings.DEFAULT_TTL_MS, WeatherConfig.DEFAULT_TTL_MS);
    }

    // ───────── LOW-MAINT.31 ─────────

    @Test
    @DisplayName("LOW-MAINT.31：适配器自带参数，不从被适配对象反查（store 与 settings 可分离）")
    void lowMaint31_adapterOwnsItsSettings() {
        WeatherSnapshotStore longTtlStore = new WeatherSnapshotStore(
                new WeatherSettings(600_000L, 8.0, 12.0, 5));
        longTtlStore.accept(wind(114.29, 30.59, 5.0, T0));
        // 适配器用短 TTL：即使仓里还是新鲜的，端口侧也要按自己的口径判过期
        WeatherSnapshotAdapter shortTtl = new WeatherSnapshotAdapter(
                longTtlStore, new WeatherSettings(1_000L, 8.0, 12.0, 5));
        assertNull(shortTtl.latest(114.29, 30.59, T0 + 5_000L),
                "端口自己的 TTL 应生效（双保险），而不是只看仓的 TTL");
        assertEquals(5.0, new WeatherSnapshotAdapter(longTtlStore)
                .latest(114.29, 30.59, T0 + 5_000L).speedMs(), 1e-12);
    }

    // ───────── LOW-MAINT.32 ─────────

    @Test
    @DisplayName("LOW-MAINT.32：端口是函数式接口（可 lambda 实现，多方法会被编译器拦）")
    void lowMaint32_portIsFunctionalInterface() {
        assertTrue(WeatherSnapshotPort.class.isAnnotationPresent(FunctionalInterface.class),
                "须标 @FunctionalInterface");
        WeatherSnapshotPort lambda = (lon, lat, now) -> new WeatherSnapshotPort.LatestWind(3.0, 90.0, now);
        assertEquals(3.0, lambda.latest(0, 0, T0).speedMs(), 1e-12);
    }

    // ───────── 与熔断联动的一处回归 ─────────

    @Test
    @DisplayName("回归：熔断仍由新帧驱动（HIGH.39 的去重不得挡住「新帧推高最不利风」）")
    void regression_tripStillDrivenByNewerFrames() {
        long now = System.currentTimeMillis();
        listener.onWeatherMessage(wind(114.29, 30.59, 5.0, now));
        assertTrue(gate.isOpen(), "5 m/s 不熔断");
        try {
            Thread.sleep(WeatherIngestListener.MIN_EVALUATE_INTERVAL_MS + 50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onWeatherMessage(wind(114.29, 30.59, 13.0, now + 1_000L));
        assertFalse(gate.isOpen(), "13 m/s 超严重阈值应熔断");
        assertEquals(java.util.List.of(GateSource.AUTO), gate.summary().gateSources());
    }
}
