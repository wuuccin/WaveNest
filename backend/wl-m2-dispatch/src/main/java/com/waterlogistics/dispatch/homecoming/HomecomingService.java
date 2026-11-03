package com.waterlogistics.dispatch.homecoming;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingReview;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.algo.inflight.NestCheckResult;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.DroneStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 返航复核与归巢资源判定——模块层编排，落款《包结构设计》行 84
 * 「<b>homecoming/  # 返航复核与归巢资源判定（吴）：调 algo.inflight 三级判定</b>」，属 wl-m2-dispatch。
 *
 * <p><b>判定在算法层，本类只做编排与落子</b>：三级降级逻辑全在 {@link HomecomingReview}；
 * 本类负责把结论送到三处下游（《84》§4.2 输出表）：
 * <ul>
 *   <li><b>B 组</b>：第一／二级落点 → 经 {@link HomecomingTrackPort#generateTrack} 生成返航航迹，
 *       取 {@code tauFine}／{@code energyExact} 并落 {@code task_log}；</li>
 *   <li><b>D 组</b>：<b>仅第二级</b> —— {@link OccupancySlotService#tryReserve} 锁 {@code occupancy_slot}，
 *       失败则按审计顺序顺延次优候选，全部失败降级第三级（占位即锁定、不可回切）；</li>
 *   <li><b>模块四</b>：返航执行指令落 {@code task_log}，并按级别回写 {@code drone.status}
 *       （返航中／迫停＋他巢／迫降）。</li>
 * </ul>
 *
 * <p><b>是否已交付区分</b>（任务 21）：{@link HomecomingInput#cargoOnboard()} 为 {@code false}
 * 时只处理无人机、<b>不动订单</b>；为 {@code true} 且第三级迫降时按任务 25 把订单置
 * {@link OrderStatus#FAILED}、{@code fail_reason} 标「迫降野外，待人工回收」并落 {@code task_log}，
 * 交人工回收流程拾取。
 *
 * <p><b>决策不完整不落执行</b>（MEDIUM.23）：第三级缺 {@code targetId}（L4 迫降点层未接）时
 * 只返回结论、不生成航迹也不下发返航指令，不臆造迫降点；有货时的订单失败记录仍照任务 25 落库。
 *
 * <h3>两种构造方式</h3>
 * <ul>
 *   <li><b>生产</b>：{@code @Autowired} 全参构造，注入 D 组台账、机巢／无人机／订单／日志与 B 组门面；</li>
 *   <li><b>无依赖</b>：保留公开无参构造，只做算法判定、跳过全部落库副作用——
 *       供 {@code RescheduleServiceTest} 等既有单测直接 {@code new HomecomingService()} 使用。</li>
 * </ul>
 */
@Service
public class HomecomingService {

    private static final Logger log = LoggerFactory.getLogger(HomecomingService.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String NEST_STATUS_AVAILABLE = "可用";
    private static final String NEST_CAPACITY_KEY = "nestCapacity";
    private static final int DEFAULT_NEST_CAPACITY = 4;

    private static final String CRUISE_SPEED_KEY = "droneCruiseSpeedMs";
    private static final double DEFAULT_CRUISE_SPEED_MS = 12.0;
    private static final String CRUISE_ALT_KEY = "homecomingCruiseAltM";
    private static final double DEFAULT_CRUISE_ALT_M = 30.0;
    private static final String WAYPOINT_COUNT_KEY = "homecomingWaypointCount";
    private static final int DEFAULT_WAYPOINT_COUNT = 32;
    private static final String PATH_RISK_KEY = "homecomingPathRisk";
    private static final double DEFAULT_PATH_RISK = 0.1;
    private static final String ENERGY_RATE_KEY = "homecomingEnergyRatePerSecPct";
    private static final double DEFAULT_ENERGY_RATE_PER_SEC_PCT = 0.04;

    /** 返航航迹／返航指令／迫降回收的 task_log 操作类型 */
    private static final String OP_ROUTE = "返航航迹";
    private static final String OP_COMMAND = "返航指令";
    private static final String OP_MANUAL_RECOVERY = "迫降野外-待人工回收";

    private static final String FAIL_REASON_FIELD_LANDING = "迫降野外，待人工回收";

    private static final String POSITION_AIR = "空中";
    private static final String POSITION_OTHER_NEST = "他巢临时停靠";
    private static final String POSITION_LANDING = "迫降点";

    /** 第一级迟滞记忆上限（按 orderId 有界 LRU，重启后至多退化一次） */
    private static final int PREVIOUS_TIER_CAPACITY = 1024;

    private final OccupancySlotService slotService;
    private final NestMapper nestMapper;
    private final DroneMapper droneMapper;
    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final SysParamLoader sysParamLoader;
    private final HomecomingTrackPort returnTrackPort;

    /** 上一轮落点结论（仅供第一级双阈值迟滞；有界 LRU） */
    private final Map<String, HomecomingTier> previousTiers = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HomecomingTier> eldest) {
                    return size() > PREVIOUS_TIER_CAPACITY;
                }
            });

    /**
     * 无依赖构造——只做算法判定，跳过占位、航迹、指令与订单落库副作用。
     *
     * <p>保留它是因为既有单测（{@code RescheduleServiceTest}）以 {@code new HomecomingService()} 构造；
     * Spring 容器走下面的全参构造。
     */
    public HomecomingService() {
        this(null, null, null, null, null, null, null);
    }

    @Autowired
    public HomecomingService(OccupancySlotService slotService,
            NestMapper nestMapper,
            DroneMapper droneMapper,
            OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            SysParamLoader sysParamLoader,
            HomecomingTrackPort returnTrackPort) {
        this.slotService = slotService;
        this.nestMapper = nestMapper;
        this.droneMapper = droneMapper;
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.sysParamLoader = sysParamLoader;
        this.returnTrackPort = returnTrackPort;
    }

    /**
     * 返航复核：判三级落点并给出结论，随后按级别落子。
     *
     * <p><b>HIGH.14</b>：始终走带候选台账与上一轮级别的 4 参重载，不再用 3 参版本；
     * 生产模式下调用方未传候选时，由本层从 {@code nest} ＋ {@code occupancy_slot} 组装台账。
     *
     * @param input      功能10 入口输入快照
     * @param params     阈值与判据参数，须由本层从 {@code sys_param} 读出并换算后注入
     * @param candidates 候选他巢台账；为空且依赖可用时本层自行组装
     * @return 三级落点结论（含候选他巢逐项打叉审计记录）
     */
    public HomecomingDecision reviewAndDecide(HomecomingInput input, HomecomingParams params,
            List<NestCandidate> candidates) {
        if (input == null || params == null) {
            throw new IllegalArgumentException("input 与 params 必填：判定不允许缺输入或缺阈值");
        }

        List<NestCandidate> ledger = candidates == null ? List.of() : candidates;
        HomecomingParams effectiveParams = params;
        DroneEntity drone = null;
        NestEntity homeNest = null;

        if (hasDependencies()) {
            drone = droneMapper.selectById(input.droneId());
            homeNest = nestMapper.selectById(input.homeNestId());
            effectiveParams = withArrivalEta(input, params, drone, homeNest);
            if (ledger.isEmpty()) {
                ledger = buildCandidateLedger(input, effectiveParams, homeNest);
            }
        }

        HomecomingDecision decision = HomecomingReview.review(
                input, effectiveParams, ledger, previousTierOf(input.orderId()));
        decision = reserveForcedStop(decision, effectiveParams, input.orderId());
        rememberTier(input.orderId(), decision.tier());

        log.info("返航落点结论：orderId={} tier={} targetId={} 占位请求={} 候选审计={}条 风险标记={} 判定说明={}",
                input.orderId(), decision.tier().getValue(), decision.targetId(),
                decision.needSlotRequest(), decision.candidateAudit().size(),
                decision.riskFlags(), decision.reason());

        if (hasDependencies()) {
            dispatch(decision, input, drone, homeNest);
        }
        return decision;
    }

    // ───────────────────────── 台账与 ETA ─────────────────────────

    /**
     * 候选他巢台账：排除原巢、按 {@code nest_id} 升序（<b>不按距离择优</b>，见《84》第 5 章），
     * 能耗按「基线 × 他巢距离／原巢距离」粗估（B 组精算接入前的最低档口径，不伪造能耗）。
     */
    private List<NestCandidate> buildCandidateLedger(HomecomingInput input,
            HomecomingParams params, NestEntity homeNest) {
        Long eta = params.arrivalEtaMs();
        if (eta == null) {
            log.info("候选台账为空：到达时刻不可算（无法做第二级时隙判定）orderId={}", input.orderId());
            return List.of();
        }
        GeoPoint home = pointOf(homeNest);
        if (home == null) {
            log.warn("候选台账为空：原巢坐标缺失，无法估算他巢返程能耗 nestId={} orderId={}",
                    input.homeNestId(), input.orderId());
            return List.of();
        }
        GeoPoint start = input.returnStartPoint();
        double homeDistance = distanceM(start, home);
        TimeWindow window = arrivalWindow(eta, params);

        List<NestCandidate> ledger = new ArrayList<>();
        List<NestEntity> nests = nestMapper.selectList(new LambdaQueryWrapper<NestEntity>()
                .orderByAsc(NestEntity::getNestId));
        for (NestEntity nest : nests) {
            if (nest == null || nest.getNestId() == null
                    || nest.getNestId().equals(input.homeNestId())) {
                continue;
            }
            GeoPoint point = pointOf(nest);
            if (point == null) {
                log.warn("跳过无坐标候选机巢 nestId={} orderId={}", nest.getNestId(), input.orderId());
                continue;
            }
            int totalSlots = NEST_STATUS_AVAILABLE.equals(nest.getStatus()) || nest.getStatus() == null
                    ? capacityOf(nest)
                    : 0;
            double ratio = homeDistance < 1.0
                    ? 1.0
                    : distanceM(start, point) / homeDistance;
            double returnEnergy = input.homewardEnergyBaselinePercent() * ratio;
            List<TimeWindow> occupied = slotService.activeWindows(nest.getNestId(), window);
            ledger.add(new NestCandidate(nest.getNestId(), totalSlots, occupied, returnEnergy));
        }
        return ledger;
    }

    /** ETA 缺失时按「当前位置 → 原巢」距离／巡航速度补齐；补不齐则保持 null（时隙判据不可算）。 */
    private HomecomingParams withArrivalEta(HomecomingInput input, HomecomingParams params,
            DroneEntity drone, NestEntity homeNest) {
        if (params.arrivalEtaMs() != null) {
            return params;
        }
        GeoPoint home = pointOf(homeNest);
        if (home == null) {
            log.warn("到达时刻不可算：原巢坐标缺失 nestId={} orderId={}",
                    input.homeNestId(), input.orderId());
            return params;
        }
        double distance = distanceM(input.returnStartPoint(), home);
        double speed = cruiseSpeedOf(drone);
        long travelMs = Math.round(distance / speed * 1000.0);
        long eta = Math.max(0L, System.currentTimeMillis() + travelMs);
        return new HomecomingParams(params.batterySafetyMarginPct(), params.batteryReleaseMarginPct(),
                params.batteryAbsoluteFloorPct(), params.nestAvailFactor(), params.windMarginFactor(),
                params.slotBufferMs(), eta, params.slotHoldMs());
    }

    // ───────────────────────── 第二级占位 ─────────────────────────

    /**
     * 第二级占位落子：按审计顺序对「准入通过」的候选逐个 {@code tryReserve}，
     * 成功即锁定；全部失败则降级第三级迫降野外（不可回切第一级）。
     */
    private HomecomingDecision reserveForcedStop(HomecomingDecision decision, HomecomingParams params,
            String orderId) {
        if (decision.tier() != HomecomingTier.FORCE_STOP_OTHER_NEST || !hasDependencies()) {
            return decision;
        }
        if (params.arrivalEtaMs() == null) {
            log.warn("第二级占位时窗不可算，降级第三级：targetId={}", decision.targetId());
            return emergencyDecision(decision, "占位时窗不可算（缺预计到达时刻），按不可回切降级");
        }

        TimeWindow window = arrivalWindow(params.arrivalEtaMs(), params);
        int attempted = 0;
        for (NestCheckResult check : decision.candidateAudit()) {
            if (!check.admitted()) {
                continue;
            }
            attempted++;
            try {
                Optional<String> slotId = slotService.tryReserve(check.nestId(), window, orderId);
                if (slotId.isPresent()) {
                    return new HomecomingDecision(HomecomingTier.FORCE_STOP_OTHER_NEST,
                            check.nestId(), null, true, decision.candidateAudit(), decision.riskFlags(),
                            decision.reason() + "；占位已锁定 slotId=" + slotId.get());
                }
                log.info("候选 {} 占位未成功，顺延次优候选 orderId 审计已记录", check.nestId());
            } catch (RuntimeException e) {
                // 占位写库异常不吞：宁可本条告警失败重投，也不把「没锁上」当成「已迫停」
                throw e;
            }
        }
        return emergencyDecision(decision,
                "第二级 " + attempted + " 个准入候选占位均失败（占位即锁定，不可回切第一级）");
    }

    /** 降级第三级：不臆造迫降点（targetId／targetPosition 留空），保留候选审计与风险标记。 */
    private static HomecomingDecision emergencyDecision(HomecomingDecision origin, String why) {
        return new HomecomingDecision(HomecomingTier.EMERGENCY_LAND_FIELD, null, null, false,
                origin.candidateAudit(), origin.riskFlags(),
                "第三级命中：迫降野外（" + why + "；迫降为最后一级）");
    }

    private static TimeWindow arrivalWindow(long etaMs, HomecomingParams params) {
        long start = etaMs - params.slotBufferMs();
        long end = etaMs + params.slotHoldMs() + params.slotBufferMs();
        return new TimeWindow(start, end);
    }

    // ───────────────────────── 下游分发 ─────────────────────────

    /**
     * 按结论分发到下游（《84》§4.2 输出表三行），并按是否已交付分流订单处理。
     *
     * <p><b>MEDIUM.23</b>：缺目标标识的结论属不完整，只落「有货迫降→订单失败」的台账，
     * 不生成航迹、不下发返航指令。
     */
    private void dispatch(HomecomingDecision decision, HomecomingInput input,
            DroneEntity drone, NestEntity homeNest) {
        OrderTaskEntity order = orderTaskMapper.selectById(input.orderId());
        if (!isExecutable(decision)) {
            log.warn("决策不完整（缺 targetId），不落执行：orderId={} tier={}；按 MEDIUM.23 只保留结论与订单台账",
                    input.orderId(), decision.tier().getValue());
            settleCargoAndOrder(decision, input, order);
            return;
        }
        toRoutePlannerAndEnergy(decision, input, drone, homeNest, order);
        toFlightController(decision, input, drone);
        settleCargoAndOrder(decision, input, order);
    }

    /** 第一／二级须有落点标识；第三级 L4 迫降点未出点时不得执行。 */
    private static boolean isExecutable(HomecomingDecision decision) {
        String targetId = decision.targetId();
        return targetId != null && !targetId.isBlank();
    }

    /**
     * ① 落点结论 → <b>B 组</b>：返航航迹经 {@link HomecomingTrackPort#generateTrack} 生成，
     * 精算时长与能耗随结论落 {@code task_log}（B 不得自行改点，见《84》E-3）。
     */
    private void toRoutePlannerAndEnergy(HomecomingDecision decision, HomecomingInput input,
            DroneEntity drone, NestEntity homeNest, OrderTaskEntity order) {
        GeoPoint target = targetPointOf(decision, homeNest);
        if (target == null) {
            log.warn("返航航迹跳过：目标坐标不可得 tier={} targetId={} orderId={}",
                    decision.tier().getValue(), decision.targetId(), input.orderId());
            return;
        }
        GeoPoint start = input.returnStartPoint();
        if (distanceM(start, target) < 1e-3) {
            log.warn("返航航迹跳过：起终点重合（零长度航迹）orderId={} targetId={}",
                    input.orderId(), decision.targetId());
            return;
        }
        try {
            B_FlyableRoute route = returnTrackPort.generateTrack(new GenerateRouteCommand(
                    identifier(input.orderId()),
                    identifier("RTN-" + input.orderId()),
                    start.lon(), start.lat(), target.lon(), target.lat(),
                    cruiseAltM(), cruiseSpeedOf(drone), System.currentTimeMillis(),
                    waypointCount(), List.of(), List.of(),
                    energyRatePerSecPct(drone), pathRisk()));
            // 返航航迹落审计；航迹对象本身不入库（task_log 无航迹字段，见通用纪律）
            writeTaskLog(input.orderId(), OP_ROUTE, order == null ? null : order.getStatus(),
                    order == null ? null : order.getStatus());
            log.info("返航航迹生成完成：orderId={} tier={} targetId={} 时长={}s 精算能耗={}% 风险={}",
                    input.orderId(), decision.tier().getValue(), decision.targetId(),
                    route.tauFine(), route.energyExact(), route.pathRisk());
        } catch (RuntimeException e) {
            log.warn("返航航迹生成失败（不影响指令与订单台账）：orderId={} targetId={} 原因={}",
                    input.orderId(), decision.targetId(), e.getMessage());
        }
    }

    /**
     * ③ 返航执行指令 → <b>模块四</b>：记 {@code task_log}（opType「返航指令」）并按级别回写
     * {@code drone.status}／{@code position_type}／{@code current_nest_id}。
     *
     * <p>离散指令编码归 M4（《34》），本组在其接入前以审计记录承载同一语义，不另造 MQ 主题。
     */
    private void toFlightController(HomecomingDecision decision, HomecomingInput input, DroneEntity drone) {
        DroneStatus status = switch (decision.tier()) {
            case RETURN_HOME_NEST -> DroneStatus.RETURNING;
            case FORCE_STOP_OTHER_NEST -> DroneStatus.DIVERTED;
            case EMERGENCY_LAND_FIELD -> DroneStatus.EMERGENCY_LANDED;
        };
        String positionType = switch (decision.tier()) {
            case RETURN_HOME_NEST -> POSITION_AIR;
            case FORCE_STOP_OTHER_NEST -> POSITION_OTHER_NEST;
            case EMERGENCY_LAND_FIELD -> POSITION_LANDING;
        };
        String currentNestId = decision.tier() == HomecomingTier.FORCE_STOP_OTHER_NEST
                ? decision.targetId()
                : null;

        String fromStatus = drone == null ? null : drone.getStatus();
        try {
            droneMapper.update(null, new LambdaUpdateWrapper<DroneEntity>()
                    .eq(DroneEntity::getDroneId, input.droneId())
                    .set(DroneEntity::getStatus, status.getValue())
                    .set(DroneEntity::getPositionType, positionType)
                    .set(DroneEntity::getCurrentNestId, currentNestId));
            writeTaskLog(input.orderId(), OP_COMMAND, fromStatus, status.getValue());
            log.info("返航指令已下发：droneId={} 状态 {} → {} positionType={} currentNestId={} orderId={}",
                    input.droneId(), fromStatus, status.getValue(), positionType, currentNestId,
                    input.orderId());
        } catch (RuntimeException e) {
            log.warn("返航指令落库失败（fail-soft）：droneId={} orderId={} 原因={}",
                    input.droneId(), input.orderId(), e.getMessage());
        }
    }

    // ───────────────────────── 21／25：订单分流 ─────────────────────────

    /**
     * 任务 21／25：按 {@code cargoOnboard} 分流。
     * <ul>
     *   <li>已交付（{@code false}）：只处理无人机，<b>不动订单</b>——空机迫降后订单保持原状态（含「已完成」）；</li>
     *   <li>未交付（{@code true}）＋第三级迫降：订单置 {@link OrderStatus#FAILED}，
     *       {@code fail_reason}=「迫降野外，待人工回收」，落 {@code task_log} 供人工回收流程拾取。</li>
     * </ul>
     */
    private void settleCargoAndOrder(HomecomingDecision decision, HomecomingInput input,
            OrderTaskEntity order) {
        if (!input.cargoOnboard()) {
            log.info("返航处置：货物已交付，订单不动作 orderId={} tier={}",
                    input.orderId(), decision.tier().getValue());
            return;
        }
        if (decision.tier() != HomecomingTier.EMERGENCY_LAND_FIELD) {
            log.info("返航处置：货物未交付但仍有落点（回原巢／迫停他巢），订单保持原状态 orderId={} tier={}",
                    input.orderId(), decision.tier().getValue());
            return;
        }
        String fromStatus = order == null ? null : order.getStatus();
        if (OrderStatus.FAILED.getValue().equals(fromStatus)
                || OrderStatus.ABORTED.getValue().equals(fromStatus)) {
            log.info("被迫降订单已是终态，不重复改写 orderId={} status={}", input.orderId(), fromStatus);
            return;
        }
        try {
            int rows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                    .eq(OrderTaskEntity::getOrderId, input.orderId())
                    .ne(OrderTaskEntity::getStatus, OrderStatus.FAILED.getValue())
                    .ne(OrderTaskEntity::getStatus, OrderStatus.ABORTED.getValue())
                    .set(OrderTaskEntity::getStatus, OrderStatus.FAILED.getValue())
                    .set(OrderTaskEntity::getFailReason, FAIL_REASON_FIELD_LANDING));
            if (rows > 0) {
                writeTaskLog(input.orderId(), OP_MANUAL_RECOVERY, fromStatus,
                        OrderStatus.FAILED.getValue());
                log.warn("有货迫降野外：订单置失败并标记待人工回收 orderId={} 原状态={}",
                        input.orderId(), fromStatus);
                // C3：订单终态释放迫停占位（迫降野外通常无占位，此处为防御性释放，幂等无副作用）
                slotService.releaseByOrder(input.orderId());
            } else {
                log.info("有货迫降野外：订单状态已被并发改写，本次未落失败 orderId={}", input.orderId());
            }
        } catch (RuntimeException e) {
            log.warn("有货迫降订单落库失败（fail-soft）：orderId={} 原因={}",
                    input.orderId(), e.getMessage());
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private GeoPoint targetPointOf(HomecomingDecision decision, NestEntity homeNest) {
        return switch (decision.tier()) {
            case RETURN_HOME_NEST -> pointOf(homeNest);
            case FORCE_STOP_OTHER_NEST -> pointOf(nestMapper.selectById(decision.targetId()));
            case EMERGENCY_LAND_FIELD -> decision.targetPosition();
        };
    }

    private static GeoPoint pointOf(NestEntity nest) {
        if (nest == null || nest.getLon() == null || nest.getLat() == null) {
            return null;
        }
        double lon = nest.getLon().doubleValue();
        double lat = nest.getLat().doubleValue();
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            return null;
        }
        return new GeoPoint(lon, lat);
    }

    private static double distanceM(GeoPoint from, GeoPoint to) {
        return GeoApprox.distanceM(from.lon(), from.lat(), to.lon(), to.lat());
    }

    private int capacityOf(NestEntity nest) {
        if (nest.getCapacity() != null && nest.getCapacity() > 0) {
            return nest.getCapacity();
        }
        return sysParamLoader.getInt(NEST_CAPACITY_KEY, DEFAULT_NEST_CAPACITY);
    }

    /** 机型巡航速度优先；缺失或非法时回落 {@code droneCruiseSpeedMs}（默认 12.0）。 */
    private double cruiseSpeedOf(DroneEntity drone) {
        if (drone != null && drone.getCruiseSpeedMs() != null) {
            double value = drone.getCruiseSpeedMs().doubleValue();
            if (Double.isFinite(value) && value >= 0.1 && value <= 100.0) {
                return value;
            }
            log.warn("机型巡航速度非法，回落全局兜底 droneId={} 实测={}", drone.getDroneId(), value);
        }
        double fallback = sysParamLoader.getDouble(CRUISE_SPEED_KEY, DEFAULT_CRUISE_SPEED_MS);
        if (!Double.isFinite(fallback) || fallback < 0.1 || fallback > 100.0) {
            return DEFAULT_CRUISE_SPEED_MS;
        }
        return fallback;
    }

    /** 能耗率优先由最大续航反推（100／续航秒），否则取配置项；统一钳制到 [0,10] %/s。 */
    private double energyRatePerSecPct(DroneEntity drone) {
        double rate;
        if (drone != null && drone.getMaxEndurance() != null && drone.getMaxEndurance() > 0) {
            rate = 100.0 / drone.getMaxEndurance();
        } else {
            rate = sysParamLoader.getDouble(ENERGY_RATE_KEY, DEFAULT_ENERGY_RATE_PER_SEC_PCT);
        }
        if (!Double.isFinite(rate)) {
            rate = DEFAULT_ENERGY_RATE_PER_SEC_PCT;
        }
        return Math.max(0.0, Math.min(10.0, rate));
    }

    private double cruiseAltM() {
        double value = sysParamLoader.getDouble(CRUISE_ALT_KEY, DEFAULT_CRUISE_ALT_M);
        if (!Double.isFinite(value) || value <= 0.0 || value > 10_000.0) {
            return DEFAULT_CRUISE_ALT_M;
        }
        return value;
    }

    private int waypointCount() {
        int value = sysParamLoader.getInt(WAYPOINT_COUNT_KEY, DEFAULT_WAYPOINT_COUNT);
        return Math.max(2, Math.min(512, value));
    }

    private double pathRisk() {
        double value = sysParamLoader.getDouble(PATH_RISK_KEY, DEFAULT_PATH_RISK);
        if (!Double.isFinite(value)) {
            return DEFAULT_PATH_RISK;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** {@link GenerateRouteCommand} 的标识约束：非空白、无内部空白、≤64。 */
    private static String identifier(String raw) {
        String cleaned = raw == null ? "" : raw.strip().replaceAll("\\s+", "");
        if (cleaned.isEmpty()) {
            cleaned = "RTN";
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private void writeTaskLog(String orderId, String opType, String fromStatus, String toStatus) {
        try {
            TaskLogEntity row = new TaskLogEntity();
            row.setOrderId(orderId);
            row.setOpType(opType);
            row.setFromStatus(fromStatus);
            row.setToStatus(toStatus);
            row.setTs(LocalDateTime.ofInstant(Instant.now(), DB_ZONE));
            taskLogMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("task_log 落库失败（fail-soft）：orderId={} opType={} 原因={}",
                    orderId, opType, e.getMessage());
        }
    }

    private HomecomingTier previousTierOf(String orderId) {
        if (orderId == null) {
            return null;
        }
        return previousTiers.get(orderId);
    }

    private void rememberTier(String orderId, HomecomingTier tier) {
        if (orderId == null) {
            return;
        }
        synchronized (previousTiers) {
            previousTiers.put(orderId, tier);
        }
    }

    private boolean hasDependencies() {
        return slotService != null && nestMapper != null && droneMapper != null
                && orderTaskMapper != null && taskLogMapper != null
                && sysParamLoader != null && returnTrackPort != null;
    }
}
