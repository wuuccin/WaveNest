package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.mq.WeatherData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *   <li><b>会面点求解</b>（B4，罗才辉）：{@link #nearest(double, double, long)} —— 「某位置的风」。</li>
 * </ol>
 *
 * <h3>★ 为什么按网格点存，而不是只留一条「全局代表值」</h3>
 * <p>{@code 22} §7.2 <b>U-05 甲案</b>（2026-10-11 裁定）明确：{@code data.weather}
 * <b>按位置与时刻发多条</b>（不同站点／网格点、不同时刻各一条），
 * <b>不得每周期只发一条全局代表值</b>——因为派单侧取「最不利风况」必须按位置分别取用，
 * 只有全局单值时最不利风取值无数据可依。本类据此按 {@code (lon, lat)} 分格存储，
 * 一格只保留该点最新一条；需要「最不利」时对所有格取最大。
 *
 * <p>新鲜度按 {@code obsTs} 判（11-05 起 M1 的 {@code targetTs} 已改为
 * {@code obsTs + 300 s}，但本类<b>不读 targetTs</b>：取的是"现在这一刻的风"，
 * 预报目标时刻留给接真预报源时另行处理）。
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
 * <h3>11-05 评审关闭要点（本文件承载 5 条）</h3>
 * <ul>
 *   <li><b>{@code HIGH.39}</b>：{@code put} <b>无条件覆盖</b>。MQ 重投、多生产者、
 *       消费线程与定时补发并存时，<b>旧帧会盖掉新帧</b>——风况倒退回去，
 *       抗风判定与熔断都会按过时的风做决定，且无任何提示。现在 {@link #accept} 只在
 *       {@code 新 obsTs ≥ 旧 obsTs} 时写入，旧帧到达直接丢弃并累加
 *       {@link #staleFrameCount()}（诊断用，可在 {@code /actuator} 或日志核对）。</li>
 *   <li><b>{@code MED-BUG.72}</b>：{@link #isFresh} 原来调 {@code fresh(nowMs).isEmpty()}，
 *       <b>每次物化整表</b>（建 ArrayList 装全部新鲜条目再判空）。调用方在闸门评估与
 *       候选判定里逐帧调用，网格一大就是纯浪费。现在短路遍历：找到一个新鲜的立即返回。</li>
 *   <li><b>{@code MED-BUG.73}</b>：{@link #worstWindMsOrDefault} 的兜底值<b>未校验</b>
 *       ——传 NaN 进去，返回值就是 NaN，下游 {@code FeasibilityJudge} 拿 NaN 做
 *       {@code wind > limit} 比较恒为 false，等于悄悄放行一切。现在兜底值非有限或为负即抛。</li>
 *   <li><b>{@code MED-BUG.74}</b>：{@link #nearest} <b>无最大半径</b>——请求点离所有格
 *       都很远（比如订单货点在另一个城市）时，仍然返回某个几十公里外的格点风，
 *       调用方无从知道这个值其实毫不相干。现在超出 {@link #MAX_NEAREST_RADIUS_KM} 返回空，
 *       让调用方走自己的回落分支。</li>
 *   <li><b>{@code MED-PERF.12}（裁定）</b>：网格<b>只增不淘汰</b>——坐标一漂（浮点取整
 *       抖动、上游换了网格原点）就是新格，旧格永久占着内存且继续参与「最不利」计算。
 *       裁定：<b>TTL 用 {@code weather.ttl-ms} 的 3 倍，容量上限 4096 格</b>，见
 *       {@link #EVICT_TTL_FACTOR} 与 {@link #MAX_CELLS}。淘汰在 {@link #accept} 内顺带做
 *       （每 {@link #EVICT_SCAN_INTERVAL} 次写入扫一遍），不另起定时任务。</li>
 * </ul>
 *
 * <h3>并发</h3>
 * <p>{@code mmsi} 级并发写不存在——本类只有「MQ 消费线程写、其他线程读」两种访问，
 * 用 {@link ConcurrentHashMap} ＋ 不可变 record 值，读不阻塞写、写不阻塞读，
 * 无需分布式锁（{@code 26} §6 的最小实现口径）。{@code accept} 内的「比较再写」
 * 用 {@link ConcurrentHashMap#merge} 保证同格并发写时不会互相覆盖出旧值。
 */
@Component
public class WeatherSnapshotStore {

    /** 分格精度：1e-4 度（纬度约 11 m，28.6°N 处经度约 9.8 m），足以把同一站点归并、把不同网格点分开。 */
    static final double CELL_DEG = 1.0e-4;

    /** 1° 纬度对应的米数（与 {@code algo.estimation} 同源）。 */
    static final double METERS_PER_DEG_LAT = 111_320.0;

    /**
     * 淘汰 TTL ＝ {@code weather.ttl-ms} 的 3 倍（MED-PERF.12 裁定）。
     *
     * <p>为什么不直接用 ttl：ttl 是「还能不能用」的判据（120 s），淘汰是「还值不值得留」。
     * 留 3 倍余量是给「短时断流后再接上」的场景——断流 3 分钟、再收到同一格的风，
     * 旧格还在原处直接覆盖；若按 1 倍 ttl 淘汰，旧格已被清、新格会另起一个
     * （坐标取整抖动时尤其明显），格数又白涨一轮。
     */
    static final int EVICT_TTL_FACTOR = 3;

    /**
     * 容量上限，格（MED-PERF.12 裁定：4096）。
     *
     * <p>演示网格是 3×3＝9 格，4096 是三个数量级的余量；真接气象源（几十个站）也远远够。
     * 达到上限时按 obsTs <b>淘汰最旧的</b>直到回落到上限之下——宁可丢最旧的一批，
     * 也不要让进程在内存上无界增长（本类是无界 Map，没有外部约束）。
     */
    static final int MAX_CELLS = 4096;

    /** 每 N 次写入扫一遍过期格（避免每条消息都 O(格数) 全扫）。 */
    static final int EVICT_SCAN_INTERVAL = 64;

    /**
     * {@link #nearest} 的最大半径，km（MED-BUG.74）。
     *
     * <p>取 20 km：赣江演示航段全长 6.5 km，两巢相距 6.5 km，3×3 网格对角约 4.4 km；
     * 20 km 足够覆盖「订单点在航段另一头」这类正常请求，又能拒掉「货点在别的城市」
     * 这种明显不相关的情形。超过本半径返回空，由调用方回落自己的静态风况。
     */
    static final double MAX_NEAREST_RADIUS_KM = 20.0;

    /** 1 km 对应的米数 */
    private static final double METERS_PER_KM = 1000.0;

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

    /** 陈旧帧丢弃计数（{@code HIGH.39} 的诊断锚点：新帧到达后被丢弃的旧帧条数）。 */
    private final AtomicLong staleFrameCount = new AtomicLong();

    /** 淘汰计数（{@code MED-PERF.12} 的诊断锚点：因过期或超容量被移除的格数）。 */
    private final AtomicLong evictedCellCount = new AtomicLong();

    /** 写入计数（用于决定是否触发淘汰扫描）。 */
    private final AtomicLong writeCount = new AtomicLong();

    public WeatherSnapshotStore(WeatherSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("参数不得为 null");
        }
        this.settings = settings;
    }

    /**
     * 收下一条风况（同一格只保留 {@code obsTs} 更新的那条）。
     *
     * <p><b>乱序帧不会回填旧值</b>（HIGH.39）：{@code 新 obsTs < 旧 obsTs} 时丢弃并计数。
     * 等于旧 obsTs 时<b>允许覆盖</b>——同一时刻重投的同一条消息，覆盖是幂等的，
     * 但不同来源同一时刻的修正值（比如上游补发更准的一次观测）也不该被拒。
     *
     * @param data 一条 {@code WeatherData}
     * @throws IllegalArgumentException 坐标、风速或观测时刻非法时抛出
     *                                  （由监听器记警告后丢弃，不中断后续消息）
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
        CellKey key = cellOf(data.lon(), data.lat());
        // HIGH.39：merge 保证同格并发写时也按 obsTs 取新，不会被后到的旧帧覆盖
        latestByCell.merge(key, data, (oldValue, newValue) -> {
            if (newValue.obsTs() < oldValue.obsTs()) {
                staleFrameCount.incrementAndGet();
                return oldValue;
            }
            return newValue;
        });
        maybeEvict();
    }

    /**
     * 超容量时按 obsTs 淘汰最旧的格，直到回落到 {@link #MAX_CELLS} 之下（MED-PERF.12）。
     *
     * <p>不做「每写必扫」的过期清理：那是 O(格数)，而本方法在 MQ 消费线程里每条消息都要走。
     * 改为每 {@link #EVICT_SCAN_INTERVAL} 次写入清一次过期格；容量兜底则每次超额即触发
     * （超额是异常状态，本就少见，多花一次排序可接受）。
     */
    private void maybeEvict() {
        long writes = writeCount.incrementAndGet();
        if (writes % EVICT_SCAN_INTERVAL == 0L) {
            long cutoff = System.currentTimeMillis() - settings.ttlMs() * EVICT_TTL_FACTOR;
            int before = latestByCell.size();
            latestByCell.values().removeIf(data -> data.obsTs() < cutoff);
            int removed = before - latestByCell.size();
            if (removed > 0) {
                evictedCellCount.addAndGet(removed);
            }
        }
        if (latestByCell.size() > MAX_CELLS) {
            evictOldestUntilWithinCapacity();
        }
    }

    /** 超容量：按 obsTs 升序删最旧的格，直到不超过上限。 */
    private void evictOldestUntilWithinCapacity() {
        List<Map.Entry<CellKey, WeatherData>> entries = new ArrayList<>(latestByCell.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().obsTs(), b.getValue().obsTs()));
        int toRemove = entries.size() - MAX_CELLS;
        for (int i = 0; i < toRemove; i++) {
            latestByCell.remove(entries.get(i).getKey());
        }
        evictedCellCount.addAndGet(toRemove);
    }

    /** 已存的网格格数（诊断用）。 */
    public int cellCount() {
        return latestByCell.size();
    }

    /** 被丢弃的乱序旧帧条数（{@code HIGH.39} 诊断用）。 */
    public long staleFrameCount() {
        return staleFrameCount.get();
    }

    /** 已淘汰的格数（{@code MED-PERF.12} 诊断用）。 */
    public long evictedCellCount() {
        return evictedCellCount.get();
    }

    /** 最近一条风况的观测时刻，ms；无数据时为 0。 */
    public long lastObsTsMs() {
        long newest = 0L;
        for (WeatherData data : latestByCell.values()) {
            newest = Math.max(newest, data.obsTs());
        }
        return newest;
    }

    /**
     * 是否还有新鲜风况（短路遍历，不物化整表——{@code MED-BUG.72}）。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 至少有一格新鲜时 {@code true}
     */
    public boolean isFresh(long nowMs) {
        for (WeatherData data : latestByCell.values()) {
            if (isFresh(data, nowMs)) {
                return true;
            }
        }
        return false;
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
     * <p><b>兜底值本身要校验</b>（{@code MED-BUG.73}）：{@code sys_param} 里若被写成空串、
     * {@code NaN} 或负数，原实现会把 NaN 原样返回——下游 {@code wind > limit} 恒为 false，
     * <b>等于悄悄放行一切</b>。这里直接拒绝，让配置错误在第一次调用时就暴露。
     *
     * @param nowMs    当前时刻，Unix 毫秒
     * @param fallback 无新鲜风况时使用的兜底风速，m/s；须为非负有限值
     * @return 最不利风速或兜底值，m/s
     * @throws IllegalArgumentException 兜底值非有限或为负
     */
    public double worstWindMsOrDefault(long nowMs, double fallback) {
        if (!Double.isFinite(fallback) || fallback < 0.0) {
            throw new IllegalArgumentException("兜底风速须为非负有限值，实际 " + fallback
                    + "（NaN 会让下游风阻比较恒为 false，等于放行一切）");
        }
        OptionalDouble worst = worstWindMs(nowMs);
        return worst.isPresent() ? worst.getAsDouble() : fallback;
    }

    /**
     * 距给定位置<b>最近</b>的新鲜风况（供会面点求解取「某位置的风」）。
     *
     * <p>超出 {@link #MAX_NEAREST_RADIUS_KM} 时返回空（{@code MED-BUG.74}）——
     * 「最近的格点」在请求点离网格几十公里时毫无意义，返回它等于给调用方一个
     * 看起来正常的错值。
     *
     * @param lon   经度，须为有限值
     * @param lat   纬度，须为有限值
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 最近的新鲜风况；无新鲜数据、入参非法或超出最大半径时为空
     */
    public Optional<WeatherData> nearest(double lon, double lat, long nowMs) {
        return nearestWithin(lon, lat, nowMs, MAX_NEAREST_RADIUS_KM * METERS_PER_KM);
    }

    /**
     * 距给定位置<b>最近</b>的新鲜风况，附带搜索半径（{@code MED-BUG.74} 的可调形式）。
     *
     * <p>默认半径 20 km 覆盖航段全部正常场景；若某处确实需要更远（例如跨航段），
     * 用本方法显式给半径，而不是把 {@link #MAX_NEAREST_RADIUS_KM} 调大——
     * 调大常量会让所有调用方一起放宽，而真正需要放宽的往往只有一处。
     *
     * @param lon         经度
     * @param lat         纬度
     * @param nowMs       当前时刻，Unix 毫秒
     * @param maxRadiusKm 搜索半径上限，km；须为正有限值
     * @return 最近的新鲜风况；无数据或全部超出半径时为空
     * @throws IllegalArgumentException 半径非正有限值
     */
    public Optional<WeatherData> nearest(double lon, double lat, long nowMs, double maxRadiusKm) {
        if (!Double.isFinite(maxRadiusKm) || maxRadiusKm <= 0.0) {
            throw new IllegalArgumentException("搜索半径须为正有限值，实际 " + maxRadiusKm);
        }
        return nearestWithin(lon, lat, nowMs, maxRadiusKm * METERS_PER_KM);
    }

    /**
     * 最近新鲜格的实际实现（半径以米计）。
     *
     * @param maxRadiusM 半径上限，米
     */
    private Optional<WeatherData> nearestWithin(double lon, double lat, long nowMs, double maxRadiusM) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
            return Optional.empty();
        }
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
        if (best == null || bestDistance > maxRadiusM) {
            return Optional.empty();
        }
        return Optional.of(best);
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
