package com.waterlogistics.dispatch.feasibility;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import com.waterlogistics.algo.selection.FeasibleCandidatePicker;
import com.waterlogistics.dispatch.candidate.CandidateService;
import com.waterlogistics.dispatch.weather.WeatherSnapshotStore;
import com.waterlogistics.dispatch.weather.WeatherSettings;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 选机段的<b>可行性择优</b>（C1 · 10-21 任务清单 · 卢肄文）。
 *
 * <p>把「候选生成 → 逐个判可行性 → 取最近的可飞候选」这一串收在一个类里，供
 * {@code DispatchOrchestrator.orchestrate} 第 2 步（张睿哲标出的「只选不判，C1 接入」处）调用。
 *
 * <h3>它解决的具体问题</h3>
 * <p>原选机段只调 {@code CandidateSelector.selectBest}——按距离取最近的一个，
 * <b>完全不看现场风</b>。于是抗风不足的机型照样被派出去：判据要求的
 * 「11 m/s 下 M30T（抗风 10.0）被淘汰、M400（12.0）保留」在真实链上不成立。
 * 本类拿<b>全部</b>候选逐个判，近的不可飞就退到次优。
 *
 * <h3>★ 为什么本类自己读一次台账（而不是调 {@code selectBest}）</h3>
 * <p>要退到次优就必须拿到<b>全部</b>候选，而 {@code CandidateSelector.selectBest} 只返回一个，
 * 其 {@code buildInput} 又是 private——本组不改他人子包，故在本类里做一次
 * 「实体 → {@link CandidateInput}」的映射，再调 {@code CandidateService.generate}
 * （<b>public，可直接用</b>）。
 *
 * <p><b>代价与消除条件（必须写清，否则会烂成两份真相）</b>：本类的
 * {@link #buildInput} 与 {@code CandidateSelector.buildInput} <b>同源同口径</b>
 * （含「抗风/巡航未登记 → {@code NaN} 而不是 0」这条关键口径，写错会把风况闸变成永真）。
 * 一旦 {@code CandidateSelector} 暴露「返回全部候选」的方法，本类应删掉 {@link #buildInput}
 * 改为直接调用——届时只改本类，调用方零改动。
 *
 * <h3>无新鲜风况时的兜底（清单硬要求：不得给 0）</h3>
 * <p>走 {@code worstWindMsOrDefault(now, warningWindMs)}，兜底值取 {@code sys_param} 已登记的
 * {@code weatherWindWarningThreshold}（8.0 m/s）；为 0 会把下游判定变成「无风、可飞」，
 * 是危险方向的默认值。用了兜底会打 {@code WARN}，现场能看出这次判的不是实测风。
 */
@Service
public class CandidateFeasibilityPlanner {

    private static final Logger log = LoggerFactory.getLogger(CandidateFeasibilityPlanner.class);

    /** 无人机「空闲」状态词（照 23 §7；与 {@code CandidateSelector} 同口径）。 */
    private static final String STATUS_IDLE = "空闲";

    /** 机巢「可用」状态词（与 {@code CandidateSelector} 同口径）。 */
    private static final String NEST_AVAILABLE = "可用";

    // 以下两个能耗标定值在 sys_param 中无对应键，按纪律不臆造键名，
    // 沿用 FeasibilityJudge.Params.defaults() 的演示标定值（1.5 %/km、0.2 %/kg）。
    private static final double BATTERY_PER_KM_PERCENT = 1.5;
    private static final double BATTERY_PER_KG_PERCENT = 0.2;

    private final DroneMapper droneMapper;
    private final NestMapper nestMapper;
    private final CandidateService candidateService;
    private final WeatherSnapshotStore weatherStore;
    private final WeatherSettings weatherSettings;
    private final SysParamLoader sysParamLoader;

    public CandidateFeasibilityPlanner(DroneMapper droneMapper, NestMapper nestMapper,
            CandidateService candidateService, WeatherSnapshotStore weatherStore,
            WeatherSettings weatherSettings, SysParamLoader sysParamLoader) {
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
        this.candidateService = candidateService;
        this.weatherStore = weatherStore;
        this.weatherSettings = weatherSettings;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 选出<b>最近的可飞候选</b>。
     *
     * @param order 订单（取货点与载荷从订单取）
     * @return 可飞候选
     * @throws IllegalStateException 候选为空或全部不可飞时抛出，消息里带最不利风与逐组合原因
     */
    public CandidateGenerator.CandidateCombo selectFeasible(OrderTaskEntity order) {
        if (order == null) {
            throw new IllegalArgumentException("订单不得为 null");
        }
        List<CandidateGenerator.CandidateCombo> combos = candidateService.generate(
                buildInput(order), generatorParams());

        long nowMs = System.currentTimeMillis();
        double fallbackWindMs = weatherSettings.warningWindMs();
        double worstWindMs = weatherStore.worstWindMsOrDefault(nowMs, fallbackWindMs);
        if (!weatherStore.isFresh(nowMs)) {
            log.warn("无新鲜风况（{} ms 内无 data.weather），选机判定按预警阈值 {} m/s 兜底"
                    + "——不取 0（0 会被当成「无风、可飞」）；请确认 M1 气象仿真在发风",
                    weatherSettings.ttlMs(), fallbackWindMs);
        }

        double payloadKg = order.getGoodsWeight() != null
                ? order.getGoodsWeight().doubleValue() : 0.0;

        FeasibleCandidatePicker.Selection selection =
                FeasibleCandidatePicker.pick(combos, worstWindMs, payloadKg, judgeParams());

        if (!selection.found()) {
            throw new IllegalStateException(String.format(
                    "订单 %s 无可飞候选：最不利风 %.1f m/s，候选 %d 个全部被淘汰——%s",
                    order.getOrderId(), worstWindMs, selection.evaluated().size(),
                    selection.rejectionSummary()));
        }
        if (selection.rejectedCount() > 0) {
            log.info("订单 {} 选机淘汰 {} 个不可飞候选（最不利风 {} m/s）：{}",
                    order.getOrderId(), selection.rejectedCount(), worstWindMs,
                    selection.rejectionSummary());
        }
        return selection.chosen();
    }

    // ───────────── 参数与台账 ─────────────

    /** 候选生成参数：两个键均已在 sys_param 登记。 */
    private CandidateGenerator.Params generatorParams() {
        return new CandidateGenerator.Params(
                sysParamLoader.getDouble("minBatteryPercent", 30.0),
                sysParamLoader.getDouble("maxNestDistanceKm", 5.0));
    }

    /**
     * 可行性判定参数：能对上的读 {@code sys_param}，对不上的用演示标定值。
     *
     * <ul>
     *   <li>{@code maxWindMs}（机型抗风未登记时的回落上限）←
     *       {@code weatherWindCriticalThreshold}（12.0）；</li>
     *   <li>{@code reserveBatteryPercent} ← {@code minBatteryPercent}（30）；</li>
     *   <li>两个能耗系数无登记键，见本类常量。</li>
     * </ul>
     */
    private FeasibilityJudge.Params judgeParams() {
        return new FeasibilityJudge.Params(
                weatherSettings.criticalWindMs(),
                sysParamLoader.getDouble("minBatteryPercent", 30.0),
                BATTERY_PER_KM_PERCENT,
                BATTERY_PER_KG_PERCENT);
    }

    /**
     * 读空闲无人机与可用机巢，组装 {@link CandidateInput}。
     *
     * <p><b>与 {@code CandidateSelector.buildInput} 同源同口径</b>：状态词、载荷、电量、
     * 机巢允许机型都按同一套取；尤其
     * <b>抗风/巡航未登记时必须给 {@code NaN}（＝未登记），不得给 0</b>——
     * 0 会被 {@code FeasibilityJudge} 当成「完全不能吹风」，把所有该机型判死；
     * 填默认值则造假机型差异。详见 {@link CandidateInput.DroneView#windLimitMs()}。
     */
    private CandidateInput buildInput(OrderTaskEntity order) {
        List<DroneEntity> drones = droneMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DroneEntity>()
                        .eq(DroneEntity::getStatus, STATUS_IDLE));
        List<NestEntity> nests = nestMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NestEntity>()
                        .eq(NestEntity::getStatus, NEST_AVAILABLE));

        double pickupLon = order.getShipLon() != null ? order.getShipLon().doubleValue() : 0.0;
        double pickupLat = order.getShipLat() != null ? order.getShipLat().doubleValue() : 0.0;
        double payloadKg = order.getGoodsWeight() != null
                ? order.getGoodsWeight().doubleValue() : 0.0;

        List<CandidateInput.NestView> nestViews = new ArrayList<>();
        for (NestEntity nest : nests) {
            List<String> allowed = nest.getSupportedModels() != null
                    ? Arrays.asList(nest.getSupportedModels().split(","))
                    : List.of();
            nestViews.add(new CandidateInput.NestView(
                    nest.getNestId(),
                    nest.getLon() != null ? nest.getLon().doubleValue() : 0.0,
                    nest.getLat() != null ? nest.getLat().doubleValue() : 0.0,
                    allowed));
        }

        List<CandidateInput.DroneView> droneViews = new ArrayList<>();
        for (DroneEntity drone : drones) {
            droneViews.add(new CandidateInput.DroneView(
                    drone.getDroneId(),
                    drone.getCurrentNestId() != null ? drone.getCurrentNestId() : drone.getHomeNestId(),
                    drone.getModel(),
                    drone.getStatus(),
                    drone.getBattery() != null ? drone.getBattery().doubleValue() : 0.0,
                    drone.getMaxPayload() != null ? drone.getMaxPayload().doubleValue() : 0.0,
                    optionalDouble(drone.getWindLimitMs()),
                    optionalDouble(drone.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews);
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记，不是 0）。 */
    private static double optionalDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : Double.NaN;
    }
}
