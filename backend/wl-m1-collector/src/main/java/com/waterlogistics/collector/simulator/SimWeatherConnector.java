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
 *   <li>时间语义＝每条同时带 {@code obsTs}（观测时刻）与 {@code targetTs}（目标时刻）；
 *       首版实测代偿，{@code targetTs} 填充值 ＝ {@code obsTs}，结构预留。</li>
 * </ul>
 * 生成策略：固定网格（2×2）四点，每点风况在基准值附近缓慢波动＋噪声。
 */
@Component
public class SimWeatherConnector implements DataSourceConnector {

    private final MqDataPublisher publisher;

    /** 网格左下角经度——随南昌航段一起挪（T1-4）：只切船不切网格，风会落在原来的地方 */
    @Value("${wavenest.sim.weather.lon0:115.850000}")
    private double lon0;
    /** 网格左下角纬度——南昌航段南端附近 */
    @Value("${wavenest.sim.weather.lat0:28.658000}")
    private double lat0;
    /** 网格步长（十进制度）——0.044°≈4.9 km，2×2 网格罩住朝阳端—八一端全程（南北跨 0.035°），四角仍贴近江面 */
    @Value("${wavenest.sim.weather.step:0.044000}")
    private double step;
    /** 每轴网格点数 */
    @Value("${wavenest.sim.weather.grid:2}")
    private int grid;
    /** 基准风速（m/s） */
    @Value("${wavenest.sim.weather.wind-base:5.0}")
    private double windBase;
    /** 阵风附加（m/s） */
    @Value("${wavenest.sim.weather.gust-add:3.0}")
    private double gustAdd;
    /** 风向基准（度） */
    @Value("${wavenest.sim.weather.dir-base:135.0}")
    private double dirBase;
    /** 波动幅度（风速 m/s） */
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

    public SimWeatherConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-weather";
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
     * 与自产风的「各点波动不同」相对，注入是<b>确定性、可复现</b>的（演示要求）。
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

        long now = System.currentTimeMillis();
        int sent = 0;
        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(windMs), round1(normalizeDeg(dirDeg)), round2(gust),
                        now, now, now));
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

    /** 网格四点坐标（供端点回显/验收核对"发的是多个位置"）。 */
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
     * 首版实测代偿：obsTs = targetTs = 0 时刻（结构预留不变）。
     */
    private void publishInitialSnapshot() {
        long t0 = System.currentTimeMillis();
        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(windBase), round1(dirBase), round2(windBase + gustAdd),
                        t0, t0, t0));
            }
        }
    }

    @Override
    public void pollOnce(long tickTs) {
        phase++;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 首版实测代偿：targetTs = obsTs = ts（结构预留，接预报后只改填充值）
        long obsTs = tickTs;

        // 注入后自产风保持在注入海况上（见 WindBias 的说明：否则注入一秒后被冲掉）
        WindBias b = this.bias;
        double windAdd = b != null ? b.windAddMs() : 0.0;
        double dirAdd = b != null ? b.dirAddDeg() : 0.0;

        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                // 缓慢波动（按相位正弦）＋随机抖动
                double wave = Math.sin(phase * 0.1 + ix + iy);
                double wind = Math.max(0.0, windBase + windAdd + wave * windJitter + rnd.nextDouble(-0.3, 0.3));
                double gust = Math.max(wind, wind + gustAdd * rnd.nextDouble(0.3, 1.0));
                double dir = normalizeDeg(dirBase + dirAdd + wave * 10.0 + rnd.nextDouble(-5, 5));

                publisher.publishWeather(new WeatherData(
                        round6(lon), round6(lat),
                        round2(wind), round1(dir), round2(gust),
                        tickTs, obsTs, obsTs));
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
