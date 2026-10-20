package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimWeatherConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 气象注入端口（10-20 B5，与卢肜文协同；口径依据《22-接口契约》§7.2 U-05 甲案）。
 *
 * <p><b>这是什么</b>：给演示与联调一个「刮一阵指定的风」的入口。原来仿真气象只能按节拍
 * 自产（基准 5 m/s 上下波动），想演示「风一大就把 A 型机刷掉、B 型机还能飞」就没法造出风来
 * ——评审看不到那条判据真的在工作。本端点补上这个能力，不改变 collector 的既有结构：
 * 它只是 {@link SimWeatherConnector} 的一个远程开关，发出去的仍是标准 {@code data.weather}。
 *
 * <p><b>发布粒度纪律（最要紧的一条，别改）</b>：{@code data.weather} <b>按位置与时刻发多条</b>，
 * 不是每周期一条全局代表值。所以「注入 11 m/s」在本端点里意味着<b>每一个网格点各发一条 11 m/s</b>
 * （默认 2×2 ＝ 4 条），而不是全网发 1 条。原因：下游要按位置取「最不利风况」——
 * 只发一条全局值，就不存在"按位置"这件事，M2 的候选判定与 M3 的会面重算都会失去输入口径。
 *
 * <p><b>为什么不直接把风速写进 sys_param</b>：{@code sys_param} 是阈值与标定参数的登记处，
 * 不是观测数据的通道。把"当前风多大"写进参数表，等于把实测值和阈值混在一处，
 * 既违反「参数只读」的既定纪律，也让数据源切换（真源替仿真）失去意义。
 *
 * <p>用法：
 * <pre>
 *   POST /sim/weather/inject?wind=11&amp;dir=135            注入 11 m/s、135°（默认四网格点各一条）
 *   POST /sim/weather/inject?wind=11&amp;gust=14&amp;persist=false  只发一轮，不改后续自产海况
 *   GET  /sim/weather                                     查看当前海况、偏置、注入计数与网格点
 *   DELETE /sim/weather/bias                              清除偏置，回到基准海况
 * </pre>
 */
@RestController
@RequestMapping("/sim/weather")
public class WeatherInjectController {

    private final SimWeatherConnector sim;

    public WeatherInjectController(SimWeatherConnector sim) {
        this.sim = sim;
    }

    /**
     * 注入一片风。
     *
     * @param wind    风速（m/s，≥0）
     * @param dir     风向（度，0–360，超出自动归一）
     * @param gust    阵风（m/s，可选；缺省取 wind × 1.2，但不得低于 wind）
     * @param persist 是否让后续自产风保持这片海况（默认 true；false 只发这一轮）
     * @return 回显：发了几个位置、每个位置坐标、注入计数
     */
    @PostMapping("/inject")
    public ResponseEntity<Map<String, Object>> inject(@RequestParam double wind,
                                                      @RequestParam(required = false) Double dir,
                                                      @RequestParam(required = false) Double gust,
                                                      @RequestParam(defaultValue = "true") boolean persist) {
        double dirDeg = dir != null ? dir : 135.0;
        double gustMs = gust != null ? gust : Double.NaN;
        try {
            int sent = sim.inject(wind, dirDeg, gustMs, persist);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "injectWeather");
            body.put("topic", "data.weather");
            body.put("granularity", "按位置与时刻发多条（U-05 甲案）");
            body.put("windMs", wind);
            body.put("dirDeg", dirDeg);
            body.put("gustMs", Double.isNaN(gustMs) ? Math.round(wind * 12) / 10.0 : gustMs);
            body.put("persist", persist);
            body.put("publishedCount", sent);
            body.put("gridPoints", gridPointsView());
            body.put("injectedCount", sim.injectedCount());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            // 参数不合法给出 400 与原因，不要 500——这是调用方的输入问题，不是服务故障
            return ResponseEntity.badRequest().body(Map.of(
                    "action", "injectWeather",
                    "error", e.getMessage()));
        }
    }

    /** 当前海况：注入偏置、注入计数、覆盖网格点（验收时用来核对"发的是多个位置"）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        SimWeatherConnector.WindBias b = sim.currentBias();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "data.weather");
        body.put("granularity", "按位置与时刻发多条（U-05 甲案）");
        body.put("injectedCount", sim.injectedCount());
        body.put("gridPointCount", sim.gridPoints());
        body.put("gridPoints", gridPointsView());
        body.put("biasActive", b != null);
        body.put("biasWindAddMs", b != null ? b.windAddMs() : 0.0);
        body.put("biasDirAddDeg", b != null ? b.dirAddDeg() : 0.0);
        return ResponseEntity.ok(body);
    }

    /** 清除注入偏置：自产风回到基准海况（演示结束复位用，保证下一场可复现）。 */
    @DeleteMapping("/bias")
    public ResponseEntity<Map<String, Object>> clearBias() {
        sim.clearBias();
        return ResponseEntity.ok(Map.of(
                "action", "clearWeatherBias",
                "biasActive", false,
                "injectedCount", sim.injectedCount()));
    }

    private List<Map<String, Object>> gridPointsView() {
        return sim.gridCoords().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lon", c[0]);
                    m.put("lat", c[1]);
                    return m;
                })
                .toList();
    }
}
