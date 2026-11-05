package com.waterlogistics.dispatch.feasibility;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import com.waterlogistics.algo.selection.FeasibleCandidatePicker;
import com.waterlogistics.dispatch.candidate.CandidateInputAssembler;
import com.waterlogistics.dispatch.candidate.CandidateQueries;
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
 * 其 {@code buildInput} 又是 private——本组不改他人子包，故在本类里读一次台账，
 * 再调 {@code CandidateService.generate}（<b>public，可直接用</b>）。
 *
 * <p><b>组装口径已收敛（Open CodeReview 修）</b>：「实体 → {@link CandidateInput}」的映射
 * 统一由 {@code CandidateInputAssembler} 提供，本类与 {@code CandidateSelector} 都只做
 * mapper 查询（含 health/status 过滤口径）再委托它。<b>不存在两份同源副本了</b>——
 * 尤其「抗风/巡航未登记 → {@code NaN} 而不是 0」这条口径只需改一处，
 * 不会出现「改一边漏一边」把风况闸变成永真。
 *
 * <h3>无新鲜风况时的兜底（清单硬要求：不得给 0）</h3>
 * <p>走 {@code worstWindMsOrDefault(now, warningWindMs)}，兜底值取 {@code sys_param} 已登记的
 * {@code weatherWindWarningThreshold}（8.0 m/s）；为 0 会把下游判定变成「无风、可飞」，
 * 是危险方向的默认值。用了兜底会打 {@code WARN}，现场能看出这次判的不是实测风。
 */
@Service
public class CandidateFeasibilityPlanner {

    private static final Logger log = LoggerFactory.getLogger(CandidateFeasibilityPlanner.class);

    // 状态词不在这里定义：查询条件统一由 CandidateQueries 提供（逐字审查），
    // 避免「本类一份、Selector 一份」的状态词副本各自漂移。

    /**
     * 单位距离耗电（%/km）——<b>演示标定值，不是实测值</b>（MED-MAINT.7）。
     *
     * <p><b>为什么还硬编码</b>：{@code sys_param} 里没有对应键（已逐个核对
     * {@code 002_sys_param.sql}），按纪律<b>不臆造键名</b>，所以不能写成
     * {@code sysParamLoader.getDouble("batteryPerKmPercent", 1.5)}——那等于在代码里
     * 造了一个数据库里不存在的参数名，运维照名字去查会查不到，且日后真登记时
     * 两边含义未必一致。
     *
     * <p><b>MED-MAINT.7 关闭的做法</b>：把系数从「散落在判定调用点的两个裸字面量」
     * 收成一个具名常量并在此写明出处与限制，同时<b>每单把它打进 DEBUG 日志</b>
     * （见 {@link #judgeParams}）——演示时按 1.5 %/km 算出来的电量余量，
     * 现场能一眼看出「这是标定值不是实测值」，不会把结论当成真实续航。
     * 真值仍需实测标定后登记 {@code sys_param}，届时只改这两行。
     */
    private static final double BATTERY_PER_KM_PERCENT = 1.5;

    /**
     * 载荷附加耗电（%/kg）——<b>演示标定值</b>，理由同 {@link #BATTERY_PER_KM_PERCENT}。
     *
     * <p>0.2 %/kg 意味着 10 kg 货物多耗 2 个百分点：与 1.5 %/km 相比，
     * 一次 2 km 的往返（3 %）就已盖过载荷项，故本值对结论影响很小；
     * 真正决定电量闸的是距离与保留比例。
     */
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
            /** 机巢到货点距离（km，粗估直线，与组合同口径；组合缺失时 {@code NaN}） */
            double distKm,
            /**
             * 该组合是否「从无人机当前所在巢出发」（11-05 新增）。
             *
             * <p>硬判据 1 要求「候选中出现至少一个非无人机当前所在巢的组合」——
             * 光看 {@code nestId} 无法判断，必须与该机自己的巢比对。值由生成器在
             * 组装时算好随组合透传，避免前端/核对脚本再去查一次机台账。
             */
            boolean fromDroneHomeNest,
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
                    combo != null && combo.nestId().equals(nestOfDrone(evaluated, combo.droneId())),
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
            double worstWindMs,
            /** 本次组装出的输入（11-05 新增：判定结果需要回查机的归属巢） */
            CandidateInput input) {
    }

    /**
     * 「生成候选 → 取风况 → 逐个判定 → 择优」公共管线。
     * 两个对外方法只在此基础上各做各的收尾（抛异常 / 透出原因），判定口径完全一致。
     */
    private Evaluated evaluate(OrderTaskEntity order) {
        CandidateInput input = buildInput(order);
        List<CandidateGenerator.CandidateCombo> combos = candidateService.generate(
                input, generatorParams());

        long nowMs = System.currentTimeMillis();
        double fallbackWindMs = weatherSettings.warningWindMs();
        double worstWindMs = weatherStore.worstWindMsOrDefault(nowMs, fallbackWindMs);
        if (!weatherStore.isFresh(nowMs)) {
            log.warn("无新鲜风况（{} ms 内无 data.weather），选机判定按预警阈值 {} m/s 兜底"
                    + "——不取 0（0 会被当成「无风、可飞」）；请确认 M1 气象仿真在发风",
                    weatherSettings.ttlMs(), fallbackWindMs);
        }

        // 载荷直接取组装产物（Open CodeReview：这里原有一份独立的 0.0 兜底，
        // 与 buildInput 的守卫重复且会漂移——单一事实源是 CandidateInput.payloadKg()）
        double payloadKg = input.payloadKg();

        FeasibleCandidatePicker.Selection selection = FeasibleCandidatePicker.pick(
                combos, worstWindMs, payloadKg, judgeParams(input));
        return new Evaluated(selection, combos, worstWindMs, input);
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
     *   <li>{@code maxWindMs}（机型抗风未登记时的回落上限）← <b>见 {@link #conservativeWindFallbackMs}</b>
     *       （评审 MEDIUM.16：不再用 {@code criticalWindMs=12.0}，那是全部已登记机型里最宽松的一档）；</li>
     *   <li>{@code reserveBatteryPercent} ← {@code minBatteryPercent}（30）；</li>
     *   <li>两个能耗系数无登记键，见 {@link #BATTERY_PER_KM_PERCENT} 与
     *       {@link #BATTERY_PER_KG_PERCENT}（MED-MAINT.7：具名常量＋DEBUG 留痕）。</li>
     * </ul>
     */
    private FeasibilityJudge.Params judgeParams(CandidateInput input) {
        double maxWindMs = conservativeWindFallbackMs(input);
        if (log.isDebugEnabled()) {
            // MED-MAINT.7：把「用的是标定值」写进日志，避免演示结论被当成实测续航
            log.debug("可行性判定参数：maxWindMs={}（抗风未登记时的回落上限）reserveBattery={}% "
                            + "batteryPerKm={}%/km batteryPerKg={}%/kg（后两项为演示标定值，sys_param 无对应键）",
                    maxWindMs, sysParamLoader.getDouble("minBatteryPercent", 30.0),
                    BATTERY_PER_KM_PERCENT, BATTERY_PER_KG_PERCENT);
        }
        return new FeasibilityJudge.Params(
                maxWindMs,
                sysParamLoader.getDouble("minBatteryPercent", 30.0),
                BATTERY_PER_KM_PERCENT,
                BATTERY_PER_KG_PERCENT);
    }

    /**
     * 机型抗风<b>未登记</b>时的回落上限（m/s）——<b>取保守值，不再用 12.0</b>（评审 MEDIUM.16 · 硬判据 4）。
     *
     * <p><b>原缺陷</b>：回落值取 {@code criticalWindMs()}（12.0），那是「气象熔断」阈值，
     * 也是全部已登记机型里抗风最高的一档。于是 <b>未登记抗风恰恰是最宽松的</b>——
     * 台账漏填一行的机型反而比填了的更"能扛风"，等于用数据缺失换豁免。
     *
     * <p><b>新口径</b>：取两个已登记值的较小者——
     * <ol>
     *   <li><b>本次候选集里</b>已登记机型抗风上限的最小值（同一批任务里最弱的那架能扛多少）；</li>
     *   <li>{@code weatherWindWarningThreshold}（8.0 m/s，{@link WeatherSettings#warningWindMs()}）——
     *       本项目的"需要开始注意的风"。</li>
     * </ol>
     * 候选集里没有一个登记值时退回预警阈值。结果只会比原口径<b>更严或持平</b>，不可能更宽松。
     *
     * <p>取候选集而非全表：未登记的机器若混在一批都很强的机型里，不该因为表里别处有个弱机型
     * 就被压到那个值；同批比较才是「同一风速下 A 淘汰、B 保留」的真实语境。
     *
     * <p>判定端在原因里会写明「按保守口径判」，现场能看出这是回落而非真值。
     */
    private double conservativeWindFallbackMs(CandidateInput input) {
        double warning = weatherSettings.warningWindMs();
        double registeredMin = Double.POSITIVE_INFINITY;
        for (CandidateInput.DroneView drone : input.drones()) {
            if (drone.hasWindLimit() && drone.windLimitMs() < registeredMin) {
                registeredMin = drone.windLimitMs();
            }
        }
        double fallback = Math.min(warning, registeredMin);
        if (!Double.isFinite(fallback) || fallback <= 0) {
            return warning;
        }
        return fallback;
    }

    /**
     * 读空闲无人机与可用机巢，委托 {@code CandidateInputAssembler} 组装 {@link CandidateInput}。
     *
     * <p><b>组装口径只有一份</b>（{@code CandidateInputAssembler}）：状态词、载荷、电量、
     * 机巢允许机型都按同一套取；尤其
     * <b>抗风/巡航未登记时必须给 {@code NaN}（＝未登记），不得给 0</b>——
     * 0 会被 {@code FeasibilityJudge} 当成「完全不能吹风」，把所有该机型判死；
     * 填默认值则造假机型差异。详见 {@link CandidateInput.DroneView#windLimitMs()}。
     */
    /**
     * 该无人机当前所在巢（11-05 新增）。
     *
     * <p>只用于给 {@link CandidateVerdict#fromDroneHomeNest()} 供值，不参与任何判定；
     * 查不到时返回 {@code null}，比较结果即为 {@code false}（＝不认定「同巢」，
     * 方向安全：宁可多说一个跨巢候选，也不要把跨巢候选误报成同巢而漏掉判据）。
     */
    private static String nestOfDrone(Evaluated evaluated, String droneId) {
        for (CandidateInput.DroneView drone : evaluated.input().drones()) {
            if (drone.droneId().equals(droneId)) {
                return drone.nestId();
            }
        }
        return null;
    }

    private CandidateInput buildInput(OrderTaskEntity order) {
        List<DroneEntity> drones = droneMapper.selectList(CandidateQueries.idleAndHealthyDrones());
        List<NestEntity> nests = nestMapper.selectList(CandidateQueries.availableNestsWithOccupancy());

        // 组装逻辑收敛到 CandidateInputAssembler（Open CodeReview：两份同源副本
        // 必须收敛成一份）。mapper 查询口径（空闲＋健康 / 巢可用）由 CandidateQueries
        // 统一提供（逐字审查：条件不再两处各写一遍，新入口只能拿到同一份）。
        // MEDIUM.13 守卫（缺经纬度/载重即抛）随组装器生效——生产主路径由此受保护。
        return CandidateInputAssembler.assemble(order, drones, nests);
    }
}
