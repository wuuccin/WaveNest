package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.connector.DataSourceConnector;
import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.AisMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * AIS 仿真连接器（演示期数据源）。
 *
 * <p>沿江直线航迹：给一个起点、初始航向与航速，每个节拍沿航向推进一段距离并叠加噪声。
 * 【变向注入】：{@link #injectTurn(double)} 修改航向后继续按新航向发航迹——
 * 这是 E 组 6b 意图变更判据的触发源（《编码分工表》验收：点「变向」后航向角改变并持续发新航迹）。
 *
 * <p>坐标推进采用等距直线近似（演示够用）；真实 AIS 接入时整体替换本实现，下游零改动。
 */
@Component
public class SimAisConnector implements DataSourceConnector {

    private final MqDataPublisher publisher;

    /** 起始经度（十进制度）——南昌段朝阳端（航段西南端，1 号巢一带） */
    @Value("${wavenest.sim.ais.start-lon:115.861000}")
    private double startLon;
    /** 起始纬度（十进制度）——南昌段朝阳端 */
    @Value("${wavenest.sim.ais.start-lat:28.664000}")
    private double startLat;
    /** 初始航向（度，正北为 0 顺时针）——朝阳端 → 八一端方位角约 21°（朝上游） */
    @Value("${wavenest.sim.ais.start-cog:21.0}")
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

    /** 当前状态（可被变向注入修改） */
    private double curLon;
    private double curLat;
    private volatile double cogDeg;
    private volatile double sogValue;

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

    /** 重置到起点（供演示反复开场）。 */
    public void reset() {
        this.curLon = startLon;
        this.curLat = startLat;
        this.cogDeg = startCog;
        this.sogValue = sogKnots;
    }

    @Override
    public void pollOnce(long tickTs) {
        // 1) 按当前航向/航速推进一段（节 → 米/节=1852；近似：1 度纬度≈111320m，经度按纬度余弦缩放）
        double metersPerTick = sogKnots * 0.514444 * tickIntervalSeconds();
        double rad = Math.toRadians(cogDeg);
        double dLat = (metersPerTick * Math.cos(rad)) / 111320.0;
        double dLon = (metersPerTick * Math.sin(rad))
                / (111320.0 * Math.cos(Math.toRadians(curLat)));
        curLat += dLat;
        curLon += dLon;

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
     * 注入变向：船改航向后【持续】按新航向发航迹。
     *
     * @param deltaDeg 航向改变量（度，正=右转）
     * @return 变向后的新航向
     */
    public double injectTurn(double deltaDeg) {
        cogDeg = normalize(cogDeg + deltaDeg);
        return cogDeg;
    }

    /** 直接设置航向（等价注入方式之一）。 */
    public double setCog(double newCogDeg) {
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

    private static double normalize(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
