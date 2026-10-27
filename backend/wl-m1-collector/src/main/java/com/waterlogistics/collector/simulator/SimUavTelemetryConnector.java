package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.connector.DataSourceConnector;
import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.UavTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 无人机遥测仿真连接器。
 *
 * <p>按《编码分工表》：演示期无人机遥测（位置／电量／阶段），供 M4（宋冰鑫）消费。
 * 生成策略：若干架演示机绕机巢做简单往返巡航，电量线性消耗，叠加位置噪声。
 * 真实遥测走 MQTT（Mosquitto，完整版），演示期不发真实 MQTT——直接产业务 MQ（技术栈 §五）。
 */
@Component
public class SimUavTelemetryConnector implements DataSourceConnector {

    private final MqDataPublisher publisher;

    /** 演示机数量——与台账机队规模对齐（T1-5 重配后为四架，两巢各两架） */
    @Value("${wavenest.sim.uav.count:4}")
    private int uavCount;
    /** 巡航中心（机巢）经度——南昌航段中点（朝阳端 115.8610 与八一端 115.8760 之间） */
    @Value("${wavenest.sim.uav.center-lon:115.868500}")
    private double centerLon;
    /** 巡航中心纬度——南昌航段中点 */
    @Value("${wavenest.sim.uav.center-lat:28.681500}")
    private double centerLat;
    /** 巡航半径（十进制度）——0.002°≈222 m，巡航圈直径约 445 m，不出江面 */
    @Value("${wavenest.sim.uav.radius-deg:0.0020}")
    private double radiusDeg;
    /**
     * 每节拍相位推进（弧度）。
     *
     * <p>取值须与 {@code speed-ms} 自洽：实际位移速度 ≈ 半径 × 本值。半径 0.002°≈222 m 时，
     * 0.054 rad ⇒ 约 12.0 m/s，与上报 12 m/s 一致；M4 清洗按「位置差／时间差」反算
     * 不会判 {@code POSITION_JUMP}（原 0.35 的教训见 application-m1-sim.yml 注释）。
     *
     * <p>默认值就地修正而非改 {@code application-m1-sim.yml}：该文件名为
     * {@code application-m1-sim.yml}，与激活 profile {@code sim} 不匹配，Spring 从不加载它，
     * 端口外的仿真参数实际全部取自本类的默认值。
     */
    @Value("${wavenest.sim.uav.phase-step:0.054}")
    private double phaseStep;
    /** 巡航速度（m/s，用于上报 speedMs） */
    @Value("${wavenest.sim.uav.speed-ms:12.0}")
    private double speedMs;
    /** 位置噪声（十进制度） */
    @Value("${wavenest.sim.uav.noise:0.00002}")
    private double noise;

    /** 各机相位与电量（按 uav 序号维护） */
    private double[] phases;
    private double[] batteries;

    public SimUavTelemetryConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-uav";
    }

    @Override
    public void start() {
        phases = new double[uavCount];
        batteries = new double[uavCount];
        for (int i = 0; i < uavCount; i++) {
            phases[i] = 2 * Math.PI * i / Math.max(1, uavCount);
            batteries[i] = 100.0;
        }
    }

    @Override
    public void pollOnce(long tickTs) {
        if (phases == null) {
            start();
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < uavCount; i++) {
            // 圆周巡航
            phases[i] += phaseStep;
            double lon = centerLon + radiusDeg * Math.cos(phases[i]) + rnd.nextDouble(-noise, noise);
            double lat = centerLat + radiusDeg * Math.sin(phases[i]) + rnd.nextDouble(-noise, noise);
            // 航向＝圆周切向
            double heading = normalize(Math.toDegrees(phases[i]) + 90.0);
            // 电量线性消耗（演示：每节拍 0.02%），下限 5
            batteries[i] = Math.max(5.0, batteries[i] - 0.02);

            publisher.publishUavTelemetry(new UavTelemetry(
                    uavId(i),
                    round6(lon), round6(lat),
                    80.0 + 10 * i,          // altM：分高度层防混
                    round1(speedMs),
                    round1(batteries[i]),
                    round1(heading),
                    tickTs));
        }
    }

    private String uavId(int i) {
        return "UAV-" + String.format("%02d", i + 1);
    }

    private static double normalize(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // 保留 List 引用以免误加未用 import（风格占位）
    @SuppressWarnings("unused")
    private static final List<String> UNUSED = List.of();
}
