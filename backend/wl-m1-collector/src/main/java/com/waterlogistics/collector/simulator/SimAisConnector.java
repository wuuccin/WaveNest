package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.connector.DataSourceConnector;
import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.AisMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AIS 仿真连接器（演示期数据源）。
 *
 * <p>默认沿航道中心线折线推进：给一条 13 点折线、沿程起点与航速，每个节拍沿折线推进一段
 * 并叠加噪声；未配置折线时回落到旧的「起点 + 航向直线」模式。
 * 【变向注入】：{@link #injectTurn(double)} 修改航向后转为自由航向（不再贴线），
 * 直到 {@link #reset()} 恢复折线——这是 E 组 6b 意图变更判据的触发源。
 *
 * <p>坐标推进采用等距近似（演示够用）；真实 AIS 接入时整体替换本实现，下游零改动。
 */
@Component
public class SimAisConnector implements DataSourceConnector {

    /** 与 WaterwayGeometry 同值，避免两套米/度换算。 */
    private static final double METERS_PER_DEG_LAT = Math.PI * 6371000.0 / 180.0;

    private final MqDataPublisher publisher;

    /**
     * 起始经度（十进制度）——仅在未配置折线时使用。
     *
     * <p>定稿来源：彭紫情图上放样，沿程 2.40 km（115.8611, 28.6660）。
     * <p>作废：115.863/28.668（T2-7 旧值）、115.861000（T1-4 估算值）。
     */
    @Value("${wavenest.sim.ais.start-lon:115.861100}")
    private double startLon;
    /** 起始纬度（十进制度）——仅在未配置折线时使用。 */
    @Value("${wavenest.sim.ais.start-lat:28.666000}")
    private double startLat;
    /**
     * 初始航向（度，正北为 0 顺时针）——仅在未配置折线时使用。
     *
     * <p><b>2026-10-29 修复</b>：旧值 6.7° 是「起点→北巢方位角」（朝陆地机巢），
     * 不是航道走向——沿 6.7° 跑 700 秒后离航道中心线 1149 m，船开上岸。
     * 31.2° 才是沿赣江航道（朝阳→南昌→八一）的航段走向。
     */
    @Value("${wavenest.sim.ais.start-cog:31.2}")
    private double startCog;
    /** 航速（节） */
    @Value("${wavenest.sim.ais.sog-knots:8.0}")
    private double sogKnots;
    /** 船名 */
    @Value("${wavenest.sim.ais.ship-name:SIM-SHIP-01}")
    private String shipName;
    /** MMSI */
    @Value("${wavenest.sim.ais.mmsi:123456789}")
    private String mmsi;
    /** 位置噪声幅度（十进制度） */
    @Value("${wavenest.sim.ais.noise:0.00005}")
    private double noise;

    /**
     * 航道中心线折线「lon,lat;lon,lat;…」。为空＝旧的直线模式。
     * 2026-11-09 起 wl-app 的 application.yml 注入影像河心重取的 13 点。
     */
    @Value("${wavenest.sim.ais.path:}")
    private String pathSpec;
    /** 折线起点沿程（米），默认 0；演示取 2400（南昌大桥附近）。 */
    @Value("${wavenest.sim.ais.path-start-m:0}")
    private double pathStartM;

    /** 当前状态（可被变向注入修改） */
    private double curLon;
    private double curLat;
    private volatile double cogDeg;
    private volatile double sogValue;

    /** 折线缓存（解析后） */
    private double[] pathLon;
    private double[] pathLat;
    private double[] pathCum;
    /** 沿折线当前弧长（米）；自由航向模式下不使用。 */
    private double curS;
    /** true＝变向注入后的自由航向，不再贴折线。 */
    private boolean manual;

    public SimAisConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-ais";
    }

    @Override
    public void start() {
        reset();
    }

    /** 重置到起点（供演示反复开场）；有折线时定位到 path-start-m。 */
    public void reset() {
        ensurePath();
        this.sogValue = sogKnots;
        this.manual = false;
        if (hasPath()) {
            this.curS = clamp(pathStartM, 0.0, totalLength());
            pointAt(curS);
        } else {
            this.curLon = startLon;
            this.curLat = startLat;
            this.cogDeg = startCog;
        }
    }

    @Override
    public void pollOnce(long tickTs) {
        double metersPerTick = sogKnots * 0.514444 * tickIntervalSeconds();
        if (!manual && hasPath()) {
            // 1) 沿折线推进，航向取当前段的走向
            curS = Math.min(totalLength(), curS + metersPerTick);
            pointAt(curS);
        } else {
            // 1) 旧的直线推进（未配置折线，或变向注入后的自由航向）
            double rad = Math.toRadians(cogDeg);
            double dLat = (metersPerTick * Math.cos(rad)) / 111320.0;
            double dLon = (metersPerTick * Math.sin(rad))
                    / (111320.0 * Math.cos(Math.toRadians(curLat)));
            curLat += dLat;
            curLon += dLon;
        }

        // 2) 叠加噪声
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lon = curLon + rnd.nextDouble(-noise, noise);
        double lat = curLat + rnd.nextDouble(-noise, noise);

        // 3) 发布（data.ais —— 注意不是 data.ship_ais）
        AisMessage msg = new AisMessage(
                mmsi, shipName, lon, lat,
                round3(sogValue), round3(cogDeg), tickTs);
        publisher.publishAis(msg);
    }

    // ===== 变向注入（6b 触发源）=====

    /**
     * 注入变向：船改航向后转为自由航向并【持续】按新航向发航迹；
     * 调用 {@link #reset()} 后恢复贴折线。
     *
     * @param deltaDeg 航向改变量（度，正=右转）
     * @return 变向后的新航向
     */
    public double injectTurn(double deltaDeg) {
        manual = true;
        cogDeg = normalize(cogDeg + deltaDeg);
        return cogDeg;
    }

    /** 直接设置航向（等价注入方式之一），同时转为自由航向。 */
    public double setCog(double newCogDeg) {
        manual = true;
        this.cogDeg = normalize(newCogDeg);
        return this.cogDeg;
    }

    /** 设置航速（节）。 */
    public void setSog(double sogKnotsValue) {
        this.sogValue = sogKnotsValue;
    }

    public double currentCog() {
        return cogDeg;
    }

    // ===== 折线工具 =====

    private void ensurePath() {
        if (pathLon != null || pathSpec == null || pathSpec.isBlank()) {
            return;
        }
        List<double[]> pts = new ArrayList<>();
        for (String part : pathSpec.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] xy = p.split(",");
            if (xy.length != 2) {
                continue;
            }
            try {
                pts.add(new double[]{Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim())});
            } catch (NumberFormatException ignored) {
                // 脏点跳过，保持其余点可用
            }
        }
        if (pts.size() < 2) {
            return;
        }
        int n = pts.size();
        pathLon = new double[n];
        pathLat = new double[n];
        pathCum = new double[n];
        for (int i = 0; i < n; i++) {
            pathLon[i] = pts.get(i)[0];
            pathLat[i] = pts.get(i)[1];
        }
        double s = 0.0;
        pathCum[0] = 0.0;
        for (int i = 1; i < n; i++) {
            double dLat = pathLat[i] - pathLat[i - 1];
            double dLon = pathLon[i] - pathLon[i - 1];
            double latRef = Math.toRadians((pathLat[i] + pathLat[i - 1]) / 2.0);
            double dx = dLon * METERS_PER_DEG_LAT * Math.cos(latRef);
            double dy = dLat * METERS_PER_DEG_LAT;
            s += Math.hypot(dx, dy);
            pathCum[i] = s;
        }
    }

    private boolean hasPath() {
        return pathLon != null && pathLon.length >= 2;
    }

    private double totalLength() {
        return pathCum[pathCum.length - 1];
    }

    /** 取沿程 s 处的点与所在段走向，写回 curLon/curLat/cogDeg。 */
    private void pointAt(double s) {
        int n = pathLon.length;
        int seg = 0;
        while (seg < n - 2 && s > pathCum[seg + 1]) {
            seg++;
        }
        double segLen = pathCum[seg + 1] - pathCum[seg];
        double f = segLen <= 0 ? 0.0 : (s - pathCum[seg]) / segLen;
        f = clamp(f, 0.0, 1.0);
        curLon = pathLon[seg] + f * (pathLon[seg + 1] - pathLon[seg]);
        curLat = pathLat[seg] + f * (pathLat[seg + 1] - pathLat[seg]);
        double latRef = Math.toRadians((pathLat[seg] + pathLat[seg + 1]) / 2.0);
        double dx = (pathLon[seg + 1] - pathLon[seg]) * METERS_PER_DEG_LAT * Math.cos(latRef);
        double dy = (pathLat[seg + 1] - pathLat[seg]) * METERS_PER_DEG_LAT;
        cogDeg = normalize(Math.toDegrees(Math.atan2(dx, dy)));
    }

    // ===== 内部工具 =====

    /** 节拍间隔（秒），与调度器节拍保持同一配置键。 */
    private double tickIntervalSeconds() {
        return tickIntervalMillis() / 1000.0;
    }

    /** 节拍间隔毫秒——与 CollectionScheduler 读同一配置，避免两处不一致。 */
    @Value("${wavenest.sim.tick-millis:1000}")
    private long tickIntervalMillisConfig;

    private long tickIntervalMillis() {
        return tickIntervalMillisConfig;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double normalize(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
