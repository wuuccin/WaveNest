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

    /** 网格左下角经度 */
    @Value("${wavenest.sim.weather.lon0:114.290000}")
    private double lon0;
    /** 网格左下角纬度 */
    @Value("${wavenest.sim.weather.lat0:30.590000}")
    private double lat0;
    /** 网格步长（十进制度） */
    @Value("${wavenest.sim.weather.step:0.010000}")
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

    public SimWeatherConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-weather";
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

        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double lon = lon0 + step * ix;
                double lat = lat0 + step * iy;
                // 缓慢波动（按相位正弦）＋随机抖动
                double wave = Math.sin(phase * 0.1 + ix + iy);
                double wind = windBase + wave * windJitter + rnd.nextDouble(-0.3, 0.3);
                double gust = Math.max(wind, wind + gustAdd * rnd.nextDouble(0.3, 1.0));
                double dir = (dirBase + wave * 10.0 + rnd.nextDouble(-5, 5) + 360.0) % 360.0;

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
