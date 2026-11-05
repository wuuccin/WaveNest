package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.mq.WeatherData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 气象快照仓（M2 · 卢肄文 · 10-20 任务 B2 ＋ B5 消费侧）。
 *
 * <p>把 M1 发到 {@code data.weather} 的风况按<b>网格点</b>存下来，供三处取用：
 * <ol>
 *   <li><b>候选判定</b>：{@link #worstWindMs(long)} —— 现场「最不利风况」，喂
 *       {@code FeasibilityJudge} 的 {@code worstWindMs}；</li>
 *   <li><b>闸门熔断</b>：{@link #worstWindMs(long)} 超过严重阈值即
 *       {@code DispatchGateService.trip(AUTO)}（见 {@link WeatherGateTrigger}）；</li>
 *   <li><b>会面点求解</b>（B4，罗才辉）：{@link #nearest(double, double)} —— 「某位置的风」。</li>
 * </ol>
 *
 * <h3>★ 为什么按网格点存，而不是只留一条「全局代表值」</h3>
 * <p>{@code 22} §7.2 <b>U-05 甲案</b>（2026-10-11 裁定）明确：{@code data.weather}
 * <b>按位置与时刻发多条</b>（不同站点／网格点、不同时刻各一条），
 * <b>不得每周期只发一条全局代表值</b>——因为派单侧取「最不利风况」必须按位置分别取用，
 * 只有全局单值时最不利风取值无数据可依。本类据此按 {@code (lon, lat)} 分格存储，
 * 一格只保留该点最新一条；需要「最不利」时对所有格取最大。
 *
 * <p>首版<b>不接入预报</b>：{@code targetTs} 的填充值＝观测时刻（实测代偿），
 * 故本类按 {@code obsTs} 判新鲜度；后续接入预报时只换判据字段、不改结构。
 *
 * <h3>新鲜度与「没有数据」的口径</h3>
 * <ul>
 *   <li>超过 {@link WeatherSettings#ttlMs()} 未更新的格<b>不参与</b>任何取用
 *       ——「过期风况」比「没有风况」更危险，因为它看起来是有效值。</li>
 *   <li>没有新鲜数据时{@link #worstWindMs(long)} 返回<b>空</b>而不是 0.0：
 *       {@code 0 m/s} 会被下游当成「无风、可飞」，是危险方向的默认值。
 *       调用方需自行决定无数据时是否放行（B4 有 {@code windSpeedMs} 兜底键，
 *       本类不替它做决定）。</li>
 *   <li>无新鲜数据时<b>不自动解除熔断</b>（见 {@link WeatherGateTrigger}）：
 *       熔断方向必须保守。</li>
 * </ul>
 *
 * <h3>并发</h3>
 * <p>{@code mmsi} 级并发写不存在——本类只有「MQ 消费线程写、其他线程读」两种访问，
 * 用 {@link ConcurrentHashMap} ＋ 不可变 record 值，读不阻塞写、写不阻塞读，
 * 无需分布式锁（{@code 26} §6 的最小实现口径）。
 */
@Component
public class WeatherSnapshotStore {

    /** 分格精度：1e-4 度（纬度约 11 m，28.6°N 处经度约 9.8 m），足以把同一站点归并、把不同网格点分开。 */
    static final double CELL_DEG = 1.0e-4;

    /** 1° 纬度对应的米数（与 {@code algo.estimation} 同源）。 */
    static final double METERS_PER_DEG_LAT = 111_320.0;

    /**
     * 网格格号。
     *
     * @param lonE4 经度按 {@link #CELL_DEG} 取整后的格号
     * @param latE4 纬度按 {@link #CELL_DEG} 取整后的格号
     */
    private record CellKey(long lonE4, long latE4) {
    }

    private final WeatherSettings settings;

    /** {@code 格号 → 该格最新一条风况}。 */
    private final Map<CellKey, WeatherData> latestByCell = new ConcurrentHashMap<>();

    public WeatherSnapshotStore(WeatherSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        this.settings = settings;
    }

    /**
     * 收下一条风况（同一格覆盖旧值）。
     *
     * @param data 一条 {@code WeatherData}
     * @throws IllegalArgumentException 坐标或风速非法时抛出（由监听器记警告后丢弃，不中断后续消息）
     */
    public void accept(WeatherData data) {
        if (data == null) {
            throw new IllegalArgumentException("风况不得为 null");
        }
        if (!Double.isFinite(data.lon()) || data.lon() < -180.0 || data.lon() > 180.0) {
            throw new IllegalArgumentException("经度非法：" + data.lon());
        }
        if (!Double.isFinite(data.lat()) || data.lat() < -90.0 || data.lat() > 90.0) {
            throw new IllegalArgumentException("纬度非法：" + data.lat());
        }
        if (!Double.isFinite(data.windSpeedMs()) || data.windSpeedMs() < 0.0) {
            throw new IllegalArgumentException("风速非法：" + data.windSpeedMs());
        }
        if (data.obsTs() <= 0L) {
            throw new IllegalArgumentException("观测时刻非法：" + data.obsTs());
        }
        latestByCell.put(cellOf(data.lon(), data.lat()), data);
    }

    /** 已存的网格格数（诊断用）。 */
    public int cellCount() {
        return latestByCell.size();
    }

    /** 最近一条风况的观测时刻，ms；无数据时为 0。 */
    public long lastObsTsMs() {
        long newest = 0L;
        for (WeatherData data : latestByCell.values()) {
            newest = Math.max(newest, data.obsTs());
        }
        return newest;
    }

    /** 是否还有新鲜风况。 */
    public boolean isFresh(long nowMs) {
        return !fresh(nowMs).isEmpty();
    }

    /**
     * 所有<b>新鲜</b>风况（{@code nowMs − obsTs ≤ ttlMs}）。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 新鲜风况列表（顺序不保证）
     */
    public List<WeatherData> fresh(long nowMs) {
        List<WeatherData> result = new ArrayList<>(latestByCell.size());
        for (WeatherData data : latestByCell.values()) {
            if (isFresh(data, nowMs)) {
                result.add(data);
            }
        }
        return result;
    }

    /**
     * 现场<b>最不利风速</b>（所有新鲜格取最大 {@code windSpeedMs}）。
     *
     * <p>判据取风速而不是阵风：{@code weatherWindCriticalThreshold} 的语义是「风速严重阈值」，
     * 阵风另有 {@code gustMs} 字段供展示；两者混用会让熔断阈值失去可比性。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 最不利风速，m/s；<b>无新鲜数据时为空</b>（不要用 0 代替）
     */
    public OptionalDouble worstWindMs(long nowMs) {
        double worst = Double.NEGATIVE_INFINITY;
        for (WeatherData data : latestByCell.values()) {
            if (isFresh(data, nowMs) && data.windSpeedMs() > worst) {
                worst = data.windSpeedMs();
            }
        }
        return worst == Double.NEGATIVE_INFINITY ? OptionalDouble.empty() : OptionalDouble.of(worst);
    }

    /**
     * 现场<b>最不利风速</b>，无新鲜数据时返回调用方指定的兜底值。
     *
     * <p>给「必须拿到一个 double 才能往下算」的调用点用（候选可行性与闸门比较都要 double）。
     * <b>兜底值由调用方显式给出</b>，本类不替你选：无风况时给 {@code 0.0} 等于宣称
     * 「无风、可飞」，是危险方向的默认值；已登记的兜底键是 {@code windSpeedMs}
     * （{@code 002_sys_param.sql}：「代表风速兜底（无快照时）」），调用方应从那里取。
     *
     * @param nowMs    当前时刻，Unix 毫秒
     * @param fallback 无新鲜风况时使用的兜底风速，m/s
     * @return 最不利风速或兜底值，m/s
     */
    public double worstWindMsOrDefault(long nowMs, double fallback) {
        OptionalDouble worst = worstWindMs(nowMs);
        return worst.isPresent() ? worst.getAsDouble() : fallback;
    }

    /**
     * 距给定位置<b>最近</b>的新鲜风况（供会面点求解取「某位置的风」）。
     *
     * @param lon   经度
     * @param lat   纬度
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 最近的新鲜风况；无新鲜数据时为空
     */
    public Optional<WeatherData> nearest(double lon, double lat, long nowMs) {
        WeatherData best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WeatherData data : latestByCell.values()) {
            if (!isFresh(data, nowMs)) {
                continue;
            }
            double distance = planarDistanceM(lon, lat, data.lon(), data.lat());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = data;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 全部格中最新的一条（不看新鲜度，诊断与日志用）。 */
    public Optional<WeatherData> newest() {
        WeatherData newest = null;
        for (WeatherData data : latestByCell.values()) {
            if (newest == null || data.obsTs() > newest.obsTs()) {
                newest = data;
            }
        }
        return Optional.ofNullable(newest);
    }

    /** 清空（演示复位与单测用）。 */
    public void clear() {
        latestByCell.clear();
    }

    /** 当前参数（供调用方判断 TTL 与阈值口径）。 */
    public WeatherSettings settings() {
        return settings;
    }

    // ───────────── 内部 ─────────────

    private boolean isFresh(WeatherData data, long nowMs) {
        return nowMs - data.obsTs() <= settings.ttlMs();
    }

    private static CellKey cellOf(double lon, double lat) {
        return new CellKey(Math.round(lon / CELL_DEG), Math.round(lat / CELL_DEG));
    }

    /**
     * 平面近似距离，m（航段跨度只有数公里，该近似远小于其他误差来源，
     * 与 {@code WaterwayGeometry} 同一口径）。
     */
    static double planarDistanceM(double lonA, double latA, double lonB, double latB) {
        double metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians((latA + latB) / 2.0));
        double dx = (lonB - lonA) * metersPerDegLon;
        double dy = (latB - latA) * METERS_PER_DEG_LAT;
        return Math.hypot(dx, dy);
    }
}
