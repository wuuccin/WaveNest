package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.mq.WeatherData;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 最新风况快照的仓取实现：按位置与当前时刻从气象快照仓取最近的新鲜观测。
 *
 * <p>取数纪律：只读、无副作用；观测过期或缺失即返回 {@code null}，由调用方回落
 * {@code sys_param} 的静态风况，气象链路异常不得中断调度主链。
 *
 * <h3>11-05 评审关闭要点（本文件承载 3 条）</h3>
 * <ul>
 *   <li><b>{@code HIGH.38}</b>：适配器<b>原来没有任何异常兜底</b>——{@link WeatherSnapshotStore#nearest}
 *       若因并发修改、{@link WeatherData} 字段异常等抛出（{@code nearest} 内部会遍历
 *       {@code ConcurrentHashMap} 并对每条数据算距离），异常会一路冒到
 *       {@code RendezvousCoordinator.representativeWind}。那里虽有 try/catch，
 *       但<b>只有它一处</b>；本类作为「端口实现」，必须自己保证「取不到就返回 null」这条契约，
 *       不能指望每个调用方都记得包一层。现在整体包 try/catch：{@code RuntimeException}
 *       记 WARN 后返回 {@code null}（气象是可选输入，不该放大成调度失败），
 *       {@code Error} 不拦（那是 JVM 级问题，吞掉只会更难查）。</li>
 *   <li><b>{@code MED-BUG.70}</b>：{@link #toLatest} 原来只校验 {@code obsTs > 0}，
 *       <b>不拒未来时刻</b>。一条 {@code obsTs = now + 1 天} 的脏帧（时区误加、上游用秒当毫秒）
 *       会永远「新鲜」——{@code now − obsTs} 恒为负数，永远 ≤ TTL，
 *       于是这条错误风况会长期压住所有正常值（最不利风取最大，或者 nearest 距离相同）。
 *       现在加未来容差 {@link #FUTURE_TOLERANCE_MS}：超过 {@code nowMs + 容差} 的观测直接判不可用。</li>
 *   <li><b>{@code LOW-MAINT.31}</b>：原来直接依赖具体类 {@link WeatherSnapshotStore}，
 *       端口与实现其实是一体的——想换个快照来源（比如接真气象源）必须改本类。
 *       保持构造函数入参为具体类不变（{@link WeatherSnapshotStore} 是 {@code @Component}，
 *       换来源时替换它即可，改动面一样小），但把「为什么不用接口」写清楚：
 *       本类方法体只有一句 {@code nearest}，再抽一层接口只是给同一个实现多贴一个名字。
 *       <b>驱动方向改为构造注入 {@code WeatherSettings}</b>：未来容差要用 TTL 推导，
 *       不再从 {@code store.settings()} 反查——端口实现不该依赖被适配对象的内部结构。</li>
 * </ul>
 */
@Service
public class WeatherSnapshotAdapter implements WeatherSnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(WeatherSnapshotAdapter.class);

    /**
     * 未来观测的容差，ms——30 s。
     *
     * <p>不设 0 是因为上下游时钟即使 NTP 同步也会有秒级偏差，若 {@code obsTs} 只比
     * {@code nowMs} 大几毫秒就判不可用，正常帧会被偶发丢掉。30 s 足以吸收时钟抖动，
     * 又远小于任何「时区／秒当毫秒」这类真错误（后者差值是小时或 1000 倍量级）。
     */
    static final long FUTURE_TOLERANCE_MS = 30_000L;

    private final WeatherSnapshotStore store;
    private final WeatherSettings settings;

    /**
     * 生产构造（Spring 注入用）。
     *
     * <p><b>必须显式标 {@code @Autowired}</b>：本类另有一个包级可见的双参构造（单测用来注入
     * 与仓不同的 TTL），Spring 见到两个构造却没有任何 {@code @Autowired} 时，
     * 会退回「找无参构造」并抛 {@code No default constructor found} ——
     * 整个 {@code ApplicationContext} 起不来，连带打死所有集成测试。
     *
     * @param store 气象快照仓
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WeatherSnapshotAdapter(WeatherSnapshotStore store) {
        this(store, Objects.requireNonNull(store, "store 不能为空").settings());
    }

    /**
     * 可分离参数的构造（单测用）：让端口按<b>自己的</b> TTL 判新鲜度，
     * 而不是反查被适配对象的 {@code settings()}（LOW-MAINT.31）。
     *
     * @param store    气象快照仓
     * @param settings 端口侧的参数
     */
    WeatherSnapshotAdapter(WeatherSnapshotStore store, WeatherSettings settings) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.settings = Objects.requireNonNull(settings, "settings 不能为空");
    }

    @Override
    public LatestWind latest(double lon, double lat, long nowMs) {
        // 入参范围（MED-DOC.4）：取数口不制造故障，越界返回 null 让调用方回落静态风况
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            return null;
        }
        if (nowMs <= 0L) {
            return null;
        }
        WeatherData data;
        try {
            data = store.nearest(lon, lat, nowMs).orElse(null);
        } catch (RuntimeException ex) {
            // HIGH.38：气象是可选输入，取数异常只降级不放大
            log.warn("取气象快照失败，按无风况处理（调用方回落静态风况）：lon={} lat={} nowMs={}",
                    lon, lat, nowMs, ex);
            return null;
        }
        return toLatest(data, nowMs);
    }

    /**
     * 观测 → 端口对象；不可用时返回 {@code null}。
     *
     * @param data  观测（可为 null）
     * @param nowMs 当前时刻，用于拒未来观测
     */
    private LatestWind toLatest(WeatherData data, long nowMs) {
        if (data == null) {
            return null;
        }
        double speedMs = data.windSpeedMs();
        double rawDir = data.windDirDeg();
        if (!Double.isFinite(speedMs) || speedMs <= 0.0
                || !Double.isFinite(rawDir) || data.obsTs() <= 0L) {
            return null;
        }
        // MED-BUG.70：未来观测不得视为新鲜——它会永远满足 now−obsTs ≤ ttl，长期压住正常值
        if (data.obsTs() > nowMs + FUTURE_TOLERANCE_MS) {
            log.warn("丢弃未来观测：obsTs={} 比当前时刻 {} 超前超过 {} ms，按无风况处理",
                    data.obsTs(), nowMs, FUTURE_TOLERANCE_MS);
            return null;
        }
        // 双保险：store 侧已按 obsTs 判新鲜度，这里再按本适配器的 TTL 复核一次，
        // 防止 store 与适配器用了不同的 settings 实例（构造点不止一处）
        if (nowMs - data.obsTs() > settings.ttlMs()) {
            return null;
        }
        // 风向归一见 WeatherSnapshotPort.LatestWind 的紧凑构造器（不变式只在一处）
        return new LatestWind(speedMs, rawDir, data.obsTs());
    }
}
