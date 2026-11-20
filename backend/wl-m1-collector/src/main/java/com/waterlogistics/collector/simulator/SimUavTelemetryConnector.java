package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.connector.DataSourceConnector;
import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.UavTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 无人机遥测仿真连接器。
 *
 * <p>按《编码分工表》：演示期无人机遥测（位置／电量／阶段），供 M4（宋冰鑫）消费。
 * 生成策略分两种：<b>没有在飞订单</b>时每架机绕自己的机巢做小半径巡航；
 * <b>有在飞订单</b>时位置改由 {@link SimUavFlightPlan} 按计划航迹给出，飞机才会真的往船飞。
 * 电量线性消耗，两种情形都叠加位置噪声。
 * 真实遥测走 MQTT（Mosquitto，完整版），演示期不发真实 MQTT，直接产业务 MQ（技术栈 §五）。
 *
 * <p>巡航中心必须落在所属机巢上。偏航判据的基线是「机巢到会面点」的直线，
 * 仿真机若绕着两巢连线的中点飞，每一帧都会离基线数百米，被判为偏航并触发重调度。
 * 巡航半径同时须小于偏航预警阈值，否则停在机巢也会越过阈值。
 *
 * <h2>注入式手段（11-16 任务 3-8 · 缺陷 7）</h2>
 *
 * <p>给演示与联调一个「把这架机弄偏／把电量打低」的入口，使 M4 的偏航与低电量判定
 * 能被现场造出来（判据本身归宋冰鑫，本类<b>只造数据、不改判据</b>）。
 *
 * <p><b>两条纪律，改之前先读</b>：
 * <ol>
 *   <li><b>不放宽偏航阈值</b>：巡航半径仍是 {@link #radiusDeg} 的 27.8 m，偏航预警／严重阈值
 *       仍在 {@code sys_param}（{@code offtrackWarningThreshold}／{@code offtrackCriticalThreshold}），
 *       本类不读也不改。注入只在巡航位置之外<b>叠加</b>位移，不改变「停在机巢不告警」这条基线性质。</li>
 *   <li><b>偏移必须渐进</b>：M4 的 {@code TelemetryCleaner} 会按「位置差／时间差」反算速度，
 *       超过 {@code wavenest.m4.telemetry.max-speed-ms}（默认 50）即判 {@code POSITION_JUMP}
 *       并把坐标置空、改用推算值——瞬移式注入会被清洗器吃掉，告警永远出不来。因此每拍最多
 *       偏移 {@code speed-ms × Δt}（默认 12 m/拍），30 m 约 3 拍、60 m 约 5 拍；复位同样按此速率退回。</li>
 * </ol>
 *
 * <p>用法（REST 入口见 {@code SimUavInjectController}）：
 * <pre>
 *   POST /sim/uav/offtrack?uavId=UAV-01&amp;deviationM=60&amp;bearingDeg=90
 *   POST /sim/uav/battery?uavId=UAV-01&amp;percent=15
 *   GET  /sim/uav
 *   DELETE /sim/uav/injections?uavId=UAV-01
 * </pre>
 */
@Component
public class SimUavTelemetryConnector implements DataSourceConnector {

    /** 十进制度与米的换算（与单测、M4 清洗的平面近似同口径）。 */
    private static final double METERS_PER_DEGREE = 111_320.0;

    /** 偏航注入上限（米）。再大已不是「绕机巢偏一点」，而是瞬移出航段，演示没有意义。 */
    private static final double MAX_INJECT_DEVIATION_M = 5_000.0;

    /** 单拍时长兜底（秒）：首个节拍没有上一拍时刻，按 1 Hz 计。 */
    private static final double DEFAULT_TICK_SEC = 1.0;

    private final MqDataPublisher publisher;

    /**
     * 计划航迹位置来源：有在飞订单时按航迹飞，没有时退回圆周巡航。
     *
     * <p>用可选注入（{@code required = false}）而不是构造参数：连接器的既有单测直接
     * {@code new SimUavTelemetryConnector(publisher)} 起两台仿真器逐拍比对，
     * 不该为了本类被迫接数据库。运行期由 Spring 注入，单测里为空即走圆周。
     */
    private SimUavFlightPlan flightPlan;

    @Autowired(required = false)
    public void setFlightPlan(SimUavFlightPlan flightPlan) {
        this.flightPlan = flightPlan;
    }

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
     * <p>0.00025°≈27.8 m，小于偏航预警阈值 30 m，因此停在机巢的仿真机不会被判为偏航。
     * 半径不能再放大，否则机巢内的正常巡航会越过预警阈值。
     *
     * <p><b>注入不碰本值</b>：偏航靠叠加位移造，不是把巡航圈放大——放大半径等于放宽了
     * 「停巢不告警」这条基线，会把正常巡航也变成告警源。
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
     * <p>默认值写在代码里：仿真参数的唯一来源是本类的 {@code @Value} 默认值。
     * 原 {@code application-m1-sim.yml} 已于 11-06 删除——该文件与激活 profile
     * {@code sim} 不匹配，Spring 从不加载它，两处参数各说各话（网格两点／步长零点零五八度
     * 对三点／零点零三零度）。
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

    /** 注入的偏航目标位移（米，0＝未注入）；实际施加量按拍渐进逼近本值 */
    private double[] offtrackTargetM;
    /** 注入的偏航方向（度，0＝正北，90＝正东，顺时针；与航向同口径） */
    private double[] offtrackBearingDeg;
    /** 已施加的位移，十进制度。分离存经纬是为了按拍逼近时走直线 */
    private double[] offtrackLonDeg;
    private double[] offtrackLatDeg;

    /** 上一拍时刻，用于把「每拍最多 12 m」换算成与真实节拍相符的速率 */
    private long lastTickTs;

    public SimUavTelemetryConnector(MqDataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "sim-uav";
    }

    @Override
    public synchronized void start() {
        phases = new double[uavCount];
        batteries = new double[uavCount];
        offtrackTargetM = new double[uavCount];
        offtrackBearingDeg = new double[uavCount];
        offtrackLonDeg = new double[uavCount];
        offtrackLatDeg = new double[uavCount];
        lastTickTs = 0L;
        for (int i = 0; i < uavCount; i++) {
            phases[i] = 2 * Math.PI * i / Math.max(1, uavCount);
            batteries[i] = 100.0;
            offtrackBearingDeg[i] = 90.0;
        }
    }

    @Override
    public synchronized void pollOnce(long tickTs) {
        if (phases == null) {
            start();
        }
        // 节拍间隔按真实时间算：调度抖动时每拍位移不跟着抖，反算速度才稳在 speedMs 附近
        double dtSec = tickSeconds(tickTs);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Map<String, SimUavFlightPlan.Fix> fixes = fixesAt(tickTs);
        for (int i = 0; i < uavCount; i++) {
            // 机巢位置双重用途：没有在飞任务时绕它巡航，有任务时它是回程终点
            double nestLon = nestCenterLon(i);
            double nestLat = nestCenterLat(i);
            phases[i] += phaseStep;
            double lon;
            double lat;
            double heading;
            SimUavFlightPlan.Fix fix = fixes.get(uavId(i));
            if (fix != null) {
                // 有在飞订单：位置与航向按计划航迹走，圆周位置让位
                lon = fix.lon();
                lat = fix.lat();
                heading = normalize(fix.headingDeg());
            } else {
                // 停在机巢：绕自己的机巢巡航，偏航基线才从同一位置起算
                lon = nestLon + radiusDeg * Math.cos(phases[i]) + rnd.nextDouble(-noise, noise);
                lat = nestLat + radiusDeg * Math.sin(phases[i]) + rnd.nextDouble(-noise, noise);
                // 航向＝圆周切向
                heading = normalize(Math.toDegrees(phases[i]) + 90.0);
            }
            // 注入的偏航位移叠加在当前位置之上（按拍渐进，复位同样渐进）
            advanceOffset(i, nestLat, dtSec);
            lon += offtrackLonDeg[i];
            lat += offtrackLatDeg[i];
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
        lastTickTs = tickTs;
    }

    /**
     * 本轮各机按计划航迹应处的位置。
     *
     * <p>没有注入位置来源（单测直接构造连接器）或本轮没有在飞订单时返回空表，
     * 调用方据此退回圆周巡航。查库异常在 {@link SimUavFlightPlan} 内部吞掉并降级，
     * 这里不再包一层，避免把「没有在飞订单」与「查询失败」混成同一条日志。
     */
    private Map<String, SimUavFlightPlan.Fix> fixesAt(long tickTs) {
        if (flightPlan == null) {
            return Map.of();
        }
        Map<String, SimUavFlightPlan.Home> homes = new LinkedHashMap<>();
        for (int i = 0; i < uavCount; i++) {
            homes.put(uavId(i), new SimUavFlightPlan.Home(nestCenterLon(i), nestCenterLat(i)));
        }
        Map<String, SimUavFlightPlan.Fix> fixes = flightPlan.fixes(tickTs, homes, speedMs);
        return fixes == null ? Map.of() : fixes;
    }

    // ------------------------------------------------------------------
    // 注入式手段
    // ------------------------------------------------------------------

    /**
     * 注入偏航：把这架机的巡航位置整体推开 {@code deviationMeters} 米。
     *
     * <p>方向用 {@code bearingDeg} 指定（0＝正北，90＝正东）。偏航判据量的是「到航段的垂直距离」，
     * 所以实际告警值约等于位移在航段法向上的投影——演示时按机巢到会面点的大致走向选方向，
     * 东西向航段取 0／180，南北向航段取 90／270。
     *
     * <p>位移按拍渐进（每拍 ≤ {@code speed-ms × Δt}），到达目标后才稳定在目标点；这样 M4 清洗
     * 反算的速度不会越过 {@code max-speed-ms}，不会把坐标判成 {@code POSITION_JUMP} 后丢掉。
     *
     * @param uavId           机号，如 {@code UAV-01}
     * @param deviationMeters 目标位移（米，&gt;0 且 ≤ 5000）
     * @param bearingDeg      方向（度，任意实数，内部归一到 [0,360)）
     * @return 注入后的状态快照
     */
    public synchronized Injection injectOfftrack(String uavId, double deviationMeters, double bearingDeg) {
        int index = indexOf(uavId);
        if (!Double.isFinite(deviationMeters) || deviationMeters <= 0) {
            throw new IllegalArgumentException("偏航位移须大于 0 米，收到 " + deviationMeters);
        }
        if (deviationMeters > MAX_INJECT_DEVIATION_M) {
            throw new IllegalArgumentException("偏航位移上限 " + (long) MAX_INJECT_DEVIATION_M
                    + " 米，收到 " + deviationMeters + "——再大就不是「偏一点」而是瞬移出航段了");
        }
        if (!Double.isFinite(bearingDeg)) {
            throw new IllegalArgumentException("偏航方向须为有限数值（度），收到 " + bearingDeg);
        }
        ensureInitialized();
        offtrackTargetM[index] = deviationMeters;
        offtrackBearingDeg[index] = normalize(bearingDeg);
        return snapshot(index);
    }

    /**
     * 注入低电量：把这架机的电量直接打到 {@code percent}%。
     *
     * <p>不渐进：电量不是几何量，{@code TelemetryCleaner} 只校验 0–100 值域、不校验变化率，
     * 一次打到位不会触发任何清洗标志。打完仍按原速率继续每拍递减（下限 5%），
     * 因此注入 20 以下会稳定停在告警区，不会一拍就弹回去。
     *
     * @param uavId   机号
     * @param percent 目标电量（0–100）
     * @return 注入后的状态快照
     */
    public synchronized Injection injectBattery(String uavId, double percent) {
        int index = indexOf(uavId);
        if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("电量须在 0–100 之间（%），收到 " + percent);
        }
        ensureInitialized();
        batteries[index] = percent;
        return snapshot(index);
    }

    /**
     * 复位单架机：偏航位移退回 0（渐进），电量回满。
     *
     * <p>电量回满是演示纪律：下一场要从同一初值开始，否则第二遍演示时电量已经在告警区。
     */
    public synchronized Injection clearInjection(String uavId) {
        int index = indexOf(uavId);
        ensureInitialized();
        offtrackTargetM[index] = 0.0;
        batteries[index] = 100.0;
        return snapshot(index);
    }

    /** 复位全部机（演示结束／重录前调用）。偏航位移同 {@link #clearInjection(String)} 渐进退回。 */
    public synchronized void clearInjections() {
        ensureInitialized();
        for (int i = 0; i < uavCount; i++) {
            offtrackTargetM[i] = 0.0;
            batteries[i] = 100.0;
        }
    }

    /** 全部机的注入状态快照（供 GET /sim/uav 回显）。 */
    public synchronized List<Injection> injections() {
        ensureInitialized();
        List<Injection> list = new ArrayList<>(uavCount);
        for (int i = 0; i < uavCount; i++) {
            list.add(snapshot(i));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 按拍把已施加位移推向目标：未注入时目标是原点，复位也走同一条路径，
     * 因此「注入」和「复位」都不会产生跳变。
     */
    private void advanceOffset(int index, double nestLat, double dtSec) {
        double targetM = offtrackTargetM[index];
        double targetLon = 0.0;
        double targetLat = 0.0;
        if (targetM > 0) {
            double bearing = Math.toRadians(offtrackBearingDeg[index]);
            targetLat = targetM * Math.cos(bearing) / METERS_PER_DEGREE;
            targetLon = targetM * Math.sin(bearing) / (METERS_PER_DEGREE * Math.cos(Math.toRadians(nestLat)));
        }
        double deltaLon = targetLon - offtrackLonDeg[index];
        double deltaLat = targetLat - offtrackLatDeg[index];
        if (deltaLon == 0.0 && deltaLat == 0.0) {
            return;
        }
        double remainingM = Math.hypot(deltaLon * METERS_PER_DEGREE * Math.cos(Math.toRadians(nestLat)),
                deltaLat * METERS_PER_DEGREE);
        double stepM = Math.max(1.0, speedMs * dtSec);
        if (remainingM <= stepM) {
            offtrackLonDeg[index] = targetLon;
            offtrackLatDeg[index] = targetLat;
            return;
        }
        double ratio = stepM / remainingM;
        offtrackLonDeg[index] += deltaLon * ratio;
        offtrackLatDeg[index] += deltaLat * ratio;
    }

    /** 当前节拍时长（秒），钳在 [0.2, 5] 内防止时钟回拨或长暂停把位移一步拉满。 */
    private double tickSeconds(long tickTs) {
        if (lastTickTs <= 0 || tickTs <= lastTickTs) {
            return DEFAULT_TICK_SEC;
        }
        double dt = (tickTs - lastTickTs) / 1000.0;
        return Math.min(5.0, Math.max(0.2, dt));
    }

    private Injection snapshot(int index) {
        double currentM = Math.hypot(
                offtrackLonDeg[index] * METERS_PER_DEGREE * Math.cos(Math.toRadians(nestCenterLat(index))),
                offtrackLatDeg[index] * METERS_PER_DEGREE);
        return new Injection(
                uavId(index),
                offtrackTargetM[index],
                Math.round(currentM * 10.0) / 10.0,
                offtrackBearingDeg[index],
                Math.round(batteries[index] * 10.0) / 10.0);
    }

    private void ensureInitialized() {
        if (phases == null) {
            start();
        }
    }

    /** 机号 → 序号。只认当前机队规模内的 {@code UAV-XX}，不接受「随便写个号」悄悄不生效。 */
    private int indexOf(String uavId) {
        if (uavId == null || uavId.isBlank()) {
            throw new IllegalArgumentException("机号不能为空");
        }
        String trimmed = uavId.trim();
        if (!trimmed.matches("UAV-\\d{2}")) {
            throw new IllegalArgumentException("机号格式应为 UAV-01 这类两位数编号，收到 " + uavId);
        }
        int index = Integer.parseInt(trimmed.substring(4)) - 1;
        if (index < 0 || index >= uavCount) {
            throw new IllegalArgumentException("未知机号：" + uavId + "（当前机数 " + uavCount + "）");
        }
        return index;
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

    /**
     * 单架机的注入状态快照。
     *
     * @param uavId            机号
     * @param targetDeviationM 目标偏航位移（米，0＝无偏航注入）
     * @param currentDeviationM 已施加的位移（米）——渐进过程中小于目标值，注入面板据此显示进度
     * @param bearingDeg       偏航方向（度）
     * @param batteryPercent   当前电量（%）
     */
    public record Injection(String uavId, double targetDeviationM, double currentDeviationM,
                            double bearingDeg, double batteryPercent) {
    }
}
