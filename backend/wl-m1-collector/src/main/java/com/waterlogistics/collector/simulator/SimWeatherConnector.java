package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.connector.DataSourceConnector;
import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.WeatherData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 气象仿真连接器。
 *
 * <p>【U-05 落地（C 组回执 2026-10-11）】：
 * <ul>
 *   <li>发布粒度＝<b>按位置与时刻发多条</b>（网格点 × 时刻），不发一条全局代表值
 *       ——消费方（C 组派单）要按位置取「最不利风况」；</li>
 *   <li>时间语义＝每条同时带 {@code obsTs}（观测时刻）与 {@code targetTs}（目标时刻）。</li>
 * </ul>
 *
 * <p>【11-05 气象真实化（硬判据 #6）】三条口径变了，改动前后的对照：
 * <table border="1">
 *   <caption>11-05 前后的仿真口径</caption>
 *   <tr><th></th><th>11-05 前</th><th>11-05 后</th></tr>
 *   <tr><td>网格</td><td>2×2、步长 0.058°（四点互不相邻，风是四个孤立值）</td>
 *       <td>3×3、步长 0.030°（九点成网，含中心线两端与两巢）</td></tr>
 *   <tr><td>空间相关</td><td>{@code sin(phase*0.1 + ix + iy)} 只有两档离散相位，其余靠逐点独立随机数</td>
 *       <td>相位<b>全网格共享</b>，格间差异只由经纬梯度决定，逐点噪声 ≤ {@link #SPATIAL_NOISE_MAX_MS} m/s</td></tr>
 *   <tr><td>时间语义</td><td>{@code targetTs = obsTs}（实测代偿）</td>
 *       <td>{@code targetTs = obsTs + 预报时距}（默认 300 s，可配）</td></tr>
 * </table>
 *
 * <p><b>为什么「共享相位」比「每个点各自随机」更真实</b>：一片风场上相邻两点的风不是独立事件——
 * 它们同时受同一个天气系统推动，所以同一时刻相邻格点的差值应当小且有方向性（沿风向梯度）。
 * 旧实现里 {@code ix + iy} 让 (0,1)/(1,0) 两点相位完全相同、(0,0)/(1,1) 相差两档，
 * 再叠 ±0.3 m/s 独立噪声，格间差值既不单调也不随距离增长；判据「相邻格点风速差随距离单调递增」
 * 在这种实现下<b>原理上无法成立</b>。现在相位项只剩一个（{@code sin(phase*0.1) * windJitter}），
 * 格间差全部来自 ({@code ix + iy}) 的线性梯度，差值随格点距离单调、方向一致。
 *
 * <p><b>{@code targetTs} 改真之后的下游影响（已核）</b>：M2 的 {@code WeatherSnapshotStore}
 * 按 {@code obsTs} 判新鲜度、不读 {@code targetTs}；C 组候选与闸门取的是「最不利风速」，
 * 也不按 {@code targetTs} 过滤。故本次只改填充值，不改任何消费逻辑。
 * 接真预报源时同样只需替换 {@link #forecastHorizonMs} 的取值来源。
 */
@Component
public class SimWeatherConnector implements DataSourceConnector {

    /**
     * 相邻格点的风速梯度（m/s 每「格距」）。
     *
     * <p>取 0.5：默认 3×3 九点里，沿东北方向两端 ({@code ix+iy}=0 → 4) 的风速差恰为 2.0 m/s，
     * 明显大于噪声（≤0.2 m/s），「差随距离单调」在带噪声时也稳得住；
     * 又不至于让九点跨越抗风阈值的两级（阈值是 8/10/12 m/s 量级）。
     */
    static final double GRADIENT_STEP_MS = 0.5;

    /**
     * 逐点空间噪声的上限（m/s，正负各此值）。
     *
     * <p>11-05 清单原文「逐点噪声幅度 ≤0.2 m/s」。噪声必须<b>小于</b>一格梯度（0.5），
     * 否则格间差值会被噪声淹没，判据 6 的「单调」就成了掷骰子；
     * 取等号上限 0.2 ＝ 一格的 40%，留足余量。
     */
    static final double SPATIAL_NOISE_MAX_MS = 0.2;

    /**
     * 相邻格点的风向梯度（度每「格距」）。
     *
     * <p>风向的空间变化比风速平缓（一片风场的风向差通常只有几度）。
     * 取 1.5°：九点里沿东北方向两端差 6°，与旧实现的 {@code wave*10 + 随机±5}
     * 相比明显收窄，且方向一致——旧实现在相邻两点上风向可差 20°，等于两个天气系统。
     */
    static final double DIR_GRADIENT_DEG = 1.5;

    private final MqDataPublisher publisher;

    /**
     * 网格左下角经度——随航段一起挪（2026-10-28 定稿坐标，南巢 115.848 / 北巢 115.867）。
     *
     * <p>取 115.840：定稿航段是<b>西南—东北斜向</b>的，方网格要同时罩住南巢与北巢，
     * 左下角必须比南巢再往西南退一点，否则北界够、东界不够（反过来同理）。
     */
    @Value("${wavenest.sim.weather.lon0:115.840000}")
    private double lon0;
    /** 网格左下角纬度——定稿南巢 28.642 再往南留余量 */
    @Value("${wavenest.sim.weather.lat0:28.640000}")
    private double lat0;

    /**
     * 网格步长（十进制度）——0.030°（经向约 2.90 km、纬向约 3.33 km）。
     *
     * <p><b>11-05 由 0.058° 改为 0.030°</b>。旧 2×2＋0.058° 的问题不是"没罩住"，
     * 而是<b>只有四个角有风</b>：斜向航段上四角离航线最远 4.7 km，船全程只能取到角落里
     * 那点的填充值，抗风判定实际是在用一个与船位无关的数。3×3＋0.030° 后：
     * 经度 115.840–115.900、纬度 28.640–28.700，南巢 (115.848, 28.642) 与
     * 北巢 (115.867, 28.698) 都在网内，船起点 (115.8611, 28.6660) 最近格点仅 0.97 km
     * （旧实现同口径为 2.9 km）。
     *
     * <p><b>为什么不继续加密</b>：11-05 清单只要求 3×3／0.030°；再密（如 5×5）会让
     * 每节拍发布条数从 9 涨到 25，M2 侧格数同步涨，而下游取「最不利」是 O(格数) 遍历。
     * 网格密度与 {@link #grid} 联动，改一个必须改另一个。
     */
    @Value("${wavenest.sim.weather.step:0.030000}")
    private double step;

    /** 每轴网格点数（11-05 由 2 改 3，见 {@link #step} 的说明）。 */
    @Value("${wavenest.sim.weather.grid:3}")
    private int grid;

    /**
     * 预报时距（ms）：{@code targetTs = obsTs + 本值}。
     *
     * <p>11-05 前 {@code targetTs} 直接填 {@code obsTs}（实测代偿），语义上是「这条既是观测
     * 也是预报目标」，下游无法区分二者。现在按清单给 300 s（5 分钟）——与 M2 侧的
     * 快照新鲜度 TTL 同量级，即「这一条风况说的是 5 分钟后那一刻的预期」。
     *
     * <p>刻意做成配置项而不是常量：接真预报源后，时距由预报源给出，
     * 这里只保留一个可被覆盖的缺省值。
     */
    @Value("${wavenest.sim.weather.forecast-horizon-ms:300000}")
    private long forecastHorizonMs;

    /** 基准风速（m/s） */
    @Value("${wavenest.sim.weather.wind-base:5.0}")
    private double windBase;
    /** 阵风附加（m/s） */
    @Value("${wavenest.sim.weather.gust-add:3.0}")
    private double gustAdd;
    /** 风向基准（度） */
    @Value("${wavenest.sim.weather.dir-base:135.0}")
    private double dirBase;
    /** 波动幅度（风速 m/s）——只作用于<b>时间</b>维度，空间维度走 {@link #GRADIENT_STEP_MS} */
    @Value("${wavenest.sim.weather.wind-jitter:1.5}")
    private double windJitter;

    private long phase;

    /** 容器是否已就绪（初值帧只在就绪后发一次，避免打在 MQ 拓扑声明之前） */
    private volatile boolean appReady;

    /** 注入计数（验收锚点：注入 N 次 → 发出的条数应＝N × 覆盖网格点数） */
    private final java.util.concurrent.atomic.AtomicLong injectedCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** 注入模式下的场级偏置：null＝未注入（按基准值自产）；非 null 时自产风叠加此偏置 */
    private volatile WindBias bias;

    /**
     * 场级偏置：把「注入的一片风」与「自产的基础风」分离。
     *
     * <p><b>为什么要有偏置而不是直接注入一条就完</b>：注入一条风之后，下一个节拍
     * 仿真器仍会按基准值再发一轮——注入的效果会被立刻冲掉，演示时「刚注入 11 m/s，
     * 一秒后变回 5 m/s」，评审会认为注入没生效。偏置令后续自产风也保持在注入的海况，
     * 直到显式清除（{@code clearBias}）。
     *
     * @param windAddMs 风速叠加（m/s，可负）
     * @param dirAddDeg 风向叠加（度）
     */
    public record WindBias(double windAddMs, double dirAddDeg) {
    }

    /**
     * 网格规格快照（诊断／回显用）：把「九个点落在哪」一次说清。
     *
     * <p>给出 {@link #lonMax()}／{@link #latMax()} 而不是让调用方自己算 {@code lon0 + step*(grid-1)}：
     * 网格边界算错过一次（T1-4 的步长没有随坐标一起改，北巢落在网外），
     * 边界只该在一处计算。
     *
     * @param grid              每轴点数
     * @param stepDeg           步长（十进制度）
     * @param lon0              左下角经度
     * @param lat0              左下角纬度
     * @param windBase          基准风速（m/s）
     * @param windJitter        时间波动幅度（m/s）
     * @param forecastHorizonMs 预报时距（ms）
     */
    public record GridSpec(int grid, double stepDeg, double lon0, double lat0,
                           double windBase, double windJitter, long forecastHorizonMs) {

        /** 网格东界经度 */
        public double lonMax() {
            return lon0 + stepDeg * (grid - 1);
        }

        /** 网格北界纬度 */
        public double latMax() {
            return lat0 + stepDeg * (grid - 1);
        }
    }

    public SimWeatherConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-weather";
    }

    /**
     * 网格规格（诊断与验收核对用）。
     *
     * @return 当前生效的网格与时间口径
     */
    public GridSpec gridSpec() {
        return new GridSpec(grid, step, lon0, lat0, windBase, windJitter, forecastHorizonMs);
    }

    /**
     * 预报时距（ms）：{@code targetTs − obsTs} 的取值。
     *
     * @return 预报时距，毫秒
     */
    public long forecastHorizonMs() {
        return forecastHorizonMs;
    }

    /**
     * 某格在某相位的风速（内部统一出口，自产与初值帧共用）。
     *
     * <p>三项相加，缺一不可：
     * <ol>
     *   <li>{@code temporal}——<b>全网格共享</b>的相位项，即「同一时刻整片风场一起涨落」；</li>
     *   <li>{@code spatial}——({@code ix + iy}) × {@link #GRADIENT_STEP_MS} 的线性梯度，
     *       同一时刻格与格之间的差只来自这里，所以差值随格点距离单调；</li>
     *   <li>{@code noise}——逐点独立噪声，幅度被调用方限在 {@link #SPATIAL_NOISE_MAX_MS} 以内。</li>
     * </ol>
     *
     * @param phase 相位（节拍序号，全网格同一个值）
     * @param ix    经向格号
     * @param iy    纬向格号
     * @param noise 该点的独立噪声（m/s）
     * @return 风速，m/s（未做非负截断，由调用方处理）
     */
    double windAt(double phase, int ix, int iy, double noise) {
        double temporal = Math.sin(phase * 0.1) * windJitter;
        double spatial = (ix + iy) * GRADIENT_STEP_MS;
        return windBase + temporal + spatial + noise;
    }

    /**
     * 某格在某相位的风向（与 {@link #windAt} 同构）。
     *
     * @param phase 相位
     * @param ix    经向格号
     * @param iy    纬向格号
     * @return 风向，度（未归一，由调用方 {@code normalizeDeg}）
     */
    double dirAt(double phase, int ix, int iy) {
        double temporal = Math.sin(phase * 0.1) * 6.0;
        double spatial = (ix + iy) * DIR_GRADIENT_DEG;
        return dirBase + temporal + spatial;
    }

    /**
     * 注入一片风：按<b>网格覆盖</b>逐点发布多条 {@code data.weather}（U-05 甲案）。
     *
     * <p><b>粒度纪律（本方法的立身之本）</b>：即使调用方只想「刮一阵 11 m/s 的风」，
     * 也不发一条全局代表值——而是把这一阵风按网格逐点落到每个位置上，
     * 每点一条（含各自的 lon/lat 与 obsTs/targetTs）。消费方（M2 候选/闸门、
     * M3 会面）据此按位置取「最不利风况」；只发一条全局值，就无从谈"按位置"。
     *
     * <p>风向按注入值统一（同一片风场），风速在基点之间保持同一值——
     * 与自产风的「各点梯度不同」相对，注入是<b>确定性、可复现</b>的（演示要求）：
     * 演示要看的是「风够大 → 某机型被刷掉」，不是「风场细节」；这里若也加梯度，
     * 一次注入会让九点里的最大风比参数值高 2 m/s，演示者按 11 报数、系统却按 13 判，对不上账。
     *
     * @param windMs  风速（m/s）
     * @param dirDeg  风向（度）
     * @param gustMs  阵风（m/s）；{@code NaN} 时取风速 × 1.2
     * @param applyBias 是否把这片风记为后续自产风的偏置（true＝持续，false＝只发这一轮）
     * @return 本次实际发出的条数（＝网格点数）
     */
    public int inject(double windMs, double dirDeg, double gustMs, boolean applyBias) {
        if (!Double.isFinite(windMs) || windMs < 0) {
            throw new IllegalArgumentException("风速须为非负有限数，实际 " + windMs);
        }
        if (!Double.isFinite(dirDeg)) {
            throw new IllegalArgumentException("风向须为有限数，实际 " + dirDeg);
        }
        double gust = Double.isNaN(gustMs) ? windMs * 1.2 : gustMs;
        if (gust < windMs) {
            // 阵风不得低于平均风：契约里 gustMs 是阵风，低于均值在物理上不成立
            throw new IllegalArgumentException(
                    "阵风不得低于风速，实际 gust=" + gust + " < wind=" + windMs);
        }

        long obsTs = System.currentTimeMillis();
        long targetTs = obsTs + forecastHorizonMs;
        int sent = 0;
        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(windMs), round1(normalizeDeg(dirDeg)), round2(gust),
                        obsTs, targetTs, obsTs));
                sent++;
            }
        }
        injectedCount.incrementAndGet();
        if (applyBias) {
            this.bias = new WindBias(windMs - windBase, dirDeg - dirBase);
        }
        return sent;
    }

    /** 当前场级偏置；未注入时为 {@code null}。 */
    public WindBias currentBias() {
        return bias;
    }

    /** 清除场级偏置：自产风回到基准海况。 */
    public void clearBias() {
        this.bias = null;
    }

    /** 注入次数（验收锚点）。 */
    public long injectedCount() {
        return injectedCount.get();
    }

    /** 覆盖网格点数（注入一次应发出的条数）。 */
    public int gridPoints() {
        return grid * grid;
    }

    /** 网格各点坐标（供端点回显/验收核对"发的是多个位置"）。 */
    public java.util.List<double[]> gridCoords() {
        java.util.List<double[]> out = new java.util.ArrayList<>(grid * grid);
        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                out.add(new double[]{round6(lon0 + step * ix), round6(lat0 + step * iy)});
            }
        }
        return out;
    }

    private static double normalizeDeg(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    @Override
    public void start() {
        this.phase = 0;
        // 初值帧不在 start() 里发：start 由调度器 @PostConstruct 触发，早于 RabbitMQ
        // 交换机声明（由 M2 监听器完成），会打出 "no exchange 'wavenest.data'"（10-20 核对实录）。
        // 若容器已就绪（如测试直调 start），仍然补发一次。
        if (appReady) {
            publishInitialSnapshot();
        }
    }

    /**
     * 容器就绪后补发初值帧：此时 MQ 拓扑（交换机/队列/绑定）已声明完毕。
     * start() 先于本事件时由这里发；start() 后于本事件（罕见）时由 start() 发，幂等不重发。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!appReady) {
            appReady = true;
            publishInitialSnapshot();
        }
    }

    /**
     * 启动初值帧（10-16）：不等第一个节拍，先按配置基准值发一轮
     * {@code data.weather} 初值（照 37 五键口径：lon / lat / wind / dir / gust），
     * 让 C 组「最不利风况」取值在 t=0 就有数据可依（U-05：按位置多条，非全局单值）。
     *
     * <p><b>11-05 改动</b>：初值帧也走 {@link #windAt()}／{@link #dirAt()}（相位 0、噪声 0），
     * 与后续节拍同一套空间梯度——否则 t=0 九点同值、t=1 突然出现梯度，M2 在头两帧
     * 会看到「最不利风速」无故跳一格。确定性可复现（噪声 0）保持不变。
     *
     * <p>时间语义与自产一致：{@code targetTs = obsTs + 预报时距}。
     */
    private void publishInitialSnapshot() {
        long obsTs = System.currentTimeMillis();
        long targetTs = obsTs + forecastHorizonMs;
        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                double wind = windAt(0.0, ix, iy, 0.0);
                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(wind), round1(normalizeDeg(dirAt(0.0, ix, iy))),
                        round2(wind + gustAdd),
                        obsTs, targetTs, obsTs));
            }
        }
    }

    @Override
    public void pollOnce(long tickTs) {
        phase++;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long obsTs = tickTs;
        // 11-05：targetTs 不再等于 obsTs，按预报时距前推（硬判据 #6）
        long targetTs = obsTs + forecastHorizonMs;

        // 注入后自产风保持在注入海况上（见 WindBias 的说明：否则注入一秒后被冲掉）
        WindBias b = this.bias;
        double windAdd = b != null ? b.windAddMs() : 0.0;
        double dirAdd = b != null ? b.dirAddDeg() : 0.0;

        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                // 相位项全网格共享，格间差异只来自 (ix+iy) 梯度与 ≤0.2 m/s 的逐点噪声
                double noise = rnd.nextDouble(-SPATIAL_NOISE_MAX_MS, SPATIAL_NOISE_MAX_MS);
                double wind = Math.max(0.0, windAdd + windAt(phase, ix, iy, noise));
                double gust = Math.max(wind, wind + gustAdd * rnd.nextDouble(0.3, 1.0));
                double dir = normalizeDeg(dirAdd + dirAt(phase, ix, iy));

                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(wind), round1(dir), round2(gust),
                        obsTs, targetTs, obsTs));
            }
        }
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
