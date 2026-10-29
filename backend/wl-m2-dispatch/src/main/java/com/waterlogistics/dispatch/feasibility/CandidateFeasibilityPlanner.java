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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * 单个候选的判定透出（E3）：组合标识＋机型/机巢/无人机＋结论＋逐条中文原因。
     *
     * <p>原因文案沿用 {@link FeasibilityJudge} 原文（机型抗风不足、电量不足等），<b>未改一字</b>；
     * 机型单独成列是为了让「落选机型」能直接被看到——原因句里有机型，但结构化字段更稳。
     *
     * <p><b>10-29 新增 {@code distKm}</b>：机巢到取货点距离（km，与组合上的粗估直线同口径），
     * 从 {@link CandidateGenerator.CandidateCombo#distKm()} 原样透传——演示讲解时
     * 「为什么选它」除了可行性结论还应能报出「离船多远」，结构化字段比拼原因句可靠。
     */
    public record CandidateVerdict(
            String comboId,
            String model,
            String nestId,
            String droneId,
            /** 机巢到取货点距离（km，粗估直线，与组合同口径；组合缺失时 {@code NaN}） */
            double distKm,
            FeasibilityJudge.Verdict verdict,
            List<String> reasons) {

        public CandidateVerdict {
            reasons = List.copyOf(reasons);
        }

        public boolean feasible() {
            return verdict == FeasibilityJudge.Verdict.FEASIBLE;
        }
    }

    /**
     * 选机结果＋全部候选的判定与原因（E3 新返回结构）。
     *
     * @param chosen     被选中的候选；没有可行候选时为 {@code null}
     * @param candidates 全部候选的判定（顺序与生成端一致，供逐机型透出原因）
     */
    public record FeasibleSelection(
            CandidateGenerator.CandidateCombo chosen,
            List<CandidateVerdict> candidates) {

        public FeasibleSelection {
            candidates = List.copyOf(candidates);
        }

        public boolean found() {
            return chosen != null;
        }

        public int rejectedCount() {
            return (int) candidates.stream().filter(v -> !v.feasible()).count();
        }
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
        Evaluated evaluated = evaluate(order);

        FeasibleCandidatePicker.Selection selection = evaluated.selection();
        if (!selection.found()) {
            throw new IllegalStateException(String.format(
                    "订单 %s 无可飞候选：最不利风 %.1f m/s，候选 %d 个全部被淘汰——%s",
                    order.getOrderId(), evaluated.worstWindMs(), selection.evaluated().size(),
                    selection.rejectionSummary()));
        }
        if (selection.rejectedCount() > 0) {
            log.info("订单 {} 选机淘汰 {} 个不可飞候选（最不利风 {} m/s）：{}",
                    order.getOrderId(), selection.rejectedCount(), evaluated.worstWindMs(),
                    selection.rejectionSummary());
        }
        return selection.chosen();
    }

    /**
     * 选出<b>最近的可飞候选</b>，并<b>随结果带出每个候选的判定与淘汰原因</b>
     * （E3 · 10-23 任务清单：候选淘汰原因透出）。
     *
     * <p>此前 {@link #selectFeasible} 只返回胜出的一个组合，判定端已生成的中文原因
     * （机型抗风不足、电量不足等）只进了日志/异常消息，调用方与前端拿不到
     * 「每个落选机型为什么落选」。本方法把判定结果原样透出——
     * <b>不改判定逻辑、不改阈值取值、不改原因文案</b>，只做结构搬运。
     *
     * <p>与 {@link #selectFeasible} 的差异：全部候选被淘汰时<b>不抛异常</b>，
     * 而是 {@code found() == false} ＋ 逐候选原因——编排层（组长改
     * {@code DispatchOrchestrator} 那处调用时）可自行决定拒单口径。
     *
     * @param order 订单（取货点与载荷从订单取）
     * @return 选机结果；{@link FeasibleSelection#candidates()} 与入参候选顺序一致，
     *         每条含机型与逐条中文原因（文案沿用 {@link FeasibilityJudge}，未改）
     */
    public FeasibleSelection selectFeasibleWithReasons(OrderTaskEntity order) {
        if (order == null) {
            throw new IllegalArgumentException("订单不得为 null");
        }
        Evaluated evaluated = evaluate(order);

        // comboId → 组合：判定结果里只有 comboId，机型/机巢/无人机要从组合上取
        Map<String, CandidateGenerator.CandidateCombo> comboById = new HashMap<>();
        for (CandidateGenerator.CandidateCombo combo : evaluated.combos()) {
            comboById.put(combo.comboId(), combo);
        }

        List<CandidateVerdict> verdicts = new ArrayList<>(evaluated.selection().evaluated().size());
        for (FeasibilityJudge.FeasibilityResult result : evaluated.selection().evaluated()) {
            CandidateGenerator.CandidateCombo combo = comboById.get(result.comboId());
            verdicts.add(new CandidateVerdict(
                    result.comboId(),
                    combo != null ? combo.type() : null,
                    combo != null ? combo.nestId() : null,
                    combo != null ? combo.droneId() : null,
                    combo != null ? combo.distKm() : Double.NaN,
                    result.verdict(),
                    result.reasons()));
        }
        return new FeasibleSelection(evaluated.selection().chosen(), List.copyOf(verdicts));
    }

    // ───────────── 公共管线（selectFeasible 与 selectFeasibleWithReasons 共用） ─────────────

    /** 一次完整评估的内部产物：逐候选判定结果 ＋ 候选原列表 ＋ 本次用的最不利风。 */
    private record Evaluated(
            FeasibleCandidatePicker.Selection selection,
            List<CandidateGenerator.CandidateCombo> combos,
            double worstWindMs) {
    }

    /**
     * 「生成候选 → 取风况 → 逐个判定 → 择优」公共管线。
     * 两个对外方法只在此基础上各做各的收尾（抛异常 / 透出原因），判定口径完全一致。
     */
    private Evaluated evaluate(OrderTaskEntity order) {
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
        return new Evaluated(selection, combos, worstWindMs);
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
