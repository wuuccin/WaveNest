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
 * 生成策略：每架演示机绕自己的机巢做小半径巡航，电量线性消耗，叠加位置噪声。
 * 真实遥测走 MQTT（Mosquitto，完整版），演示期不发真实 MQTT，直接产业务 MQ（技术栈 §五）。
 *
 * <p>巡航中心必须落在所属机巢上。偏航判据的基线是「机巢到会面点」的直线，
 * 仿真机若绕着两巢连线的中点飞，每一帧都会离基线数百米，被判为偏航并触发重调度。
 * 巡航半径同时须小于偏航预警阈值，否则停在机巢也会越过阈值。
 */
@Component
public class SimUavTelemetryConnector implements DataSourceConnector {

    private final MqDataPublisher publisher;

    /** 演示机数量——与台账机队规模对齐（T1-5 重配后为四架，两巢各两架） */
    @Value("${wavenest.sim.uav.count:4}")
    private int uavCount;
    /**
     * 南端机巢经度。仿真机按机队顺序对半分配：前半绕南巢，后半绕北巢。
     *
     * <p>默认值与 {@code 003_seed_drone_nest.sql} 的 NEST-01 一致。机巢坐标变更时，
     * 本默认值须同步，否则仿真机又会离偏航基线数百米。
     */
    @Value("${wavenest.sim.uav.nest-south-lon:115.8480000}")
    private double southNestLon;
    /** 南端机巢纬度，取值依据同 {@link #southNestLon}。 */
    @Value("${wavenest.sim.uav.nest-south-lat:28.6420000}")
    private double southNestLat;
    /** 北端机巢经度，取值与 NEST-02 一致。 */
    @Value("${wavenest.sim.uav.nest-north-lon:115.8670000}")
    private double northNestLon;
    /** 北端机巢纬度，取值与 NEST-02 一致。 */
    @Value("${wavenest.sim.uav.nest-north-lat:28.6980000}")
    private double northNestLat;
    /**
     * 巡航半径，十进制度。
     *
     * <p>0.00025°≈27.8 m，小于偏航预警阈值 50 m，因此停在机巢的仿真机不会被判为偏航。
     * 半径不能再放大，否则机巢内的正常巡航会越过预警阈值。
     */
    @Value("${wavenest.sim.uav.radius-deg:0.00025}")
    private double radiusDeg;
    /**
     * 每节拍相位推进（弧度）。
     *
     * <p>取值须与 {@code speed-ms} 自洽：实际位移速度约等于半径乘以本值。
     * 半径 0.00025°≈27.8 m 时，0.43 rad 对应约 12.0 m/s，与上报值一致，
     * M4 清洗按「位置差／时间差」反算不会判 {@code POSITION_JUMP}。
     *
     * <p>默认值写在代码里而非 {@code application-m1-sim.yml}：该文件与激活 profile
     * {@code sim} 不匹配，Spring 从不加载它，端口外的仿真参数实际全部取自本类的默认值。
     */
    @Value("${wavenest.sim.uav.phase-step:0.43}")
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
            // 每架机绕自己的机巢巡航，偏航基线才从同一位置起算
            phases[i] += phaseStep;
            double nestLon = nestCenterLon(i);
            double nestLat = nestCenterLat(i);
            double lon = nestLon + radiusDeg * Math.cos(phases[i]) + rnd.nextDouble(-noise, noise);
            double lat = nestLat + radiusDeg * Math.sin(phases[i]) + rnd.nextDouble(-noise, noise);
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

    /** 第 i 架机所属机巢的经度：前半绕南巢，后半绕北巢。 */
    private double nestCenterLon(int index) {
        return index < (uavCount + 1) / 2 ? southNestLon : northNestLon;
    }

    /** 第 i 架机所属机巢的纬度：前半绕南巢，后半绕北巢。 */
    private double nestCenterLat(int index) {
        return index < (uavCount + 1) / 2 ? southNestLat : northNestLat;
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
