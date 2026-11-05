package com.waterlogistics.dispatch.taskstate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.algo.estimation.OrderEta;
import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 飞行阶段推进器（F2）——按时间把在飞订单推进
 * {@code 已派发 → 去程中 → 末段交接中 → 返航中 → 已完成}。
 *
 * <p><b>写入口径（阶段一 23 起）</b>：每次阶段迁移在<b>同一事务</b>内完成三件事：
 * ① 条件更新 {@code order_task.status}（{@code WHERE order_id=? AND status=from}）；
 * ② 插入 {@code task_log} 审计；③ 条件更新 {@code drone} 状态。
 * 条件更新影响行数为 0 说明订单态已被他处改动——此时<b>不写审计</b>、记 error、本轮不推进，
 * 避免出现「日志说有迁移、订单表没变」的假审计。事实来源仍是已派发订单的 {@code meet_time}
 * 与机型巡航速度。
 *
 * <p><b>时间基准</b>：
 * <ul>
 *   <li>去程时长＝机巢到会面点的大圆距离 ÷ 巡航速度；</li>
 *   <li>起飞时刻＝会面时刻 − 去程时长；</li>
 *   <li>末段交接开始＝会面时刻（阶段一 17 起，实时位置进入接近半径时可提前切换）；</li>
 *   <li>返航开始＝会面时刻 ＋ {@code sys_param.tHandoverMinSec}；</li>
 *   <li>已完成＝返航开始 ＋ 去程时长（返程按同一机型速度回原巢）。</li>
 * </ul>
 *
 * <p><b>位置驱动末段（阶段一 17）</b>：{@code OUTBOUND} 阶段若 {@link DronePositionPort} 能给出
 * 该机实时位置且距会面点 ≤ {@code wavenest.m2.approach-radius-m}，提前切 {@code HANDOVER}；
 * 位置缺失或超半径则保持原时间轴推进，<b>不得因缺位置卡死</b>。
 *
 * <p><b>并发</b>：{@link #tick()} 为 {@code synchronized}，且由单线程调度器驱动，
 * 阶段判断与写回在同一临界区内（HIGH.35 的「与 tick 同锁」口径），不存在并发覆盖。
 *
 * <p><b>审计时刻单调</b>：迁移时刻按 {@code meet_time} 倒推，可能早于下单时刻；位置驱动又用
 * 当前时刻写入。故每次落库前经 {@link FlightPhaseTimeline#auditTs} 钳制，保证同一订单的
 * {@code task_log.ts} 严格递增且不早于下单时刻，不存在时间倒挂。
 *
 * <p><b>缓存失效</b>：订单态变化只把周期缓存标脏（{@link CycleState#markStale()}），下一次
 * {@code tick()} 重建，<b>不立即删除</b>——删掉会让 {@link #activeOrderIdOf} 在「派发已提交、
 * 首拍未到」的窗口内查不到该机在飞订单。
 *
 * <p><b>广播</b>：每次迁移写一条 {@code task_log}（{@code from_status}、{@code to_status}、
 * {@code op_type} 齐全），并发布 {@link FlightPhaseChangedEvent}，由组装层转成 {@code order_state}；
 * 晚到的拍会按到期顺序补记，保证事件流完整。
 */
@Component
public class FlightPhaseAdvancer {

    private static final Logger log = LoggerFactory.getLogger(FlightPhaseAdvancer.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 交接时长下界（秒）：交接完成时刻与会面时刻之差，走 sys_param，不硬编码 */
    private static final String HANDOVER_SEC_KEY = "tHandoverMinSec";

    private static final int DEFAULT_HANDOVER_SEC = 120;

    /** 飞中 ETA 用的最大巡航速度（{@code sys_param} 键名，与准入侧同源）。 */
    private static final String VMAX_CRUISE_KEY = "vMaxCruiseMs";

    private static final double DEFAULT_VMAX_CRUISE_MS = 20.0;

    /** 位置不可用时的默认接近半径（米），可由 {@code wavenest.m2.approach-radius-m} 覆盖 */
    private static final double DEFAULT_APPROACH_RADIUS_M = 50.0;

    /** resolve 失败后的退避时长（毫秒），避免每拍重复查库（MEDIUM.10）。 */
    private static final long CONTEXT_RETRY_BACKOFF_MS = 30_000L;

    /** 缺上下文告警去重键容量上限，超限清空（仅用于告警去重，无业务影响）。 */
    private static final int WARN_KEY_CAPACITY = 10_000;

    /** 周期缓存容量上限：超出后淘汰审计时刻最旧的一条（HIGH-PERF.3）。 */
    private static final int CYCLE_STATE_CAPACITY = 5_000;

    private static final List<OrderStatus> IN_FLIGHT_STATUSES = List.of(
            OrderStatus.DISPATCHED, OrderStatus.OUTBOUND, OrderStatus.HANDOVER,
            OrderStatus.RETURNING);

    private static final List<OrderStatus> FLIGHT_PHASES = List.of(
            OrderStatus.OUTBOUND, OrderStatus.HANDOVER, OrderStatus.RETURNING,
            OrderStatus.FINISHED);

    private static final String DISPATCH_OP_TYPE = "派发";

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DroneContextPort droneContextPort;
    private final DronePositionPort dronePositionPort;
    private final SysParamLoader sysParamLoader;
    private final ApplicationEventPublisher eventPublisher;
    private final DroneMapper droneMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final OccupancySlotService occupancySlotService;

    /** orderId → 版本兜底计数器（task_log 自增主键不可用时用）。 */
    private final Map<String, AtomicLong> fallbackVersions = new ConcurrentHashMap<>();

    /** 当前派发周期的阶段缓存：meet_time 或 droneId 变化即作废，避免每拍重复查库。 */
    private final Map<String, CycleState> cycleStates = new ConcurrentHashMap<>();

    /** 缺执行机上下文的告警去重键：orderId@meetMs，每周期只告警一次。 */
    private final Set<String> warnedMissingContext = ConcurrentHashMap.newKeySet();

    /** resolve 失败退避：orderId → 允许重试的时刻（毫秒），避免每拍两次查库（MEDIUM.10）。 */
    private final Map<String, Long> contextRetryAfterMs = new ConcurrentHashMap<>();

    /** 位置驱动末段的接近半径（米），走配置，不硬编（阶段一 17）。 */
    @Value("${wavenest.m2.approach-radius-m:50}")
    private double approachRadiusM;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wavenest-flight-phase-advance");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${wavenest.m2.flight-advance-enabled:true}")
    private boolean advanceEnabled;

    @Value("${wavenest.m2.flight-advance-interval-sec:2}")
    private long advanceIntervalSec;

    @Autowired
    public FlightPhaseAdvancer(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneContextPort droneContextPort,
            DronePositionPort dronePositionPort,
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher,
            DroneMapper droneMapper,
            TransactionTemplate transactionTemplate,
            OccupancySlotService occupancySlotService) {
        this(orderTaskMapper, taskLogMapper, droneContextPort, dronePositionPort, sysParamLoader,
                eventPublisher, droneMapper, transactionTemplate, Clock.systemDefaultZone(), occupancySlotService);
    }

    FlightPhaseAdvancer(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneContextPort droneContextPort,
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this(orderTaskMapper, taskLogMapper, droneContextPort, null, sysParamLoader,
                eventPublisher, null, null, clock, null);
    }

    FlightPhaseAdvancer(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneContextPort droneContextPort,
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher,
            DroneMapper droneMapper,
            Clock clock) {
        this(orderTaskMapper, taskLogMapper, droneContextPort, null, sysParamLoader,
                eventPublisher, droneMapper, null, clock, null);
    }

    FlightPhaseAdvancer(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneContextPort droneContextPort,
            DronePositionPort dronePositionPort,
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher,
            DroneMapper droneMapper,
            TransactionTemplate transactionTemplate,
            Clock clock,
            OccupancySlotService occupancySlotService) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.droneContextPort = droneContextPort;
        this.dronePositionPort = dronePositionPort;
        this.sysParamLoader = sysParamLoader;
        this.eventPublisher = eventPublisher;
        this.droneMapper = droneMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.occupancySlotService = occupancySlotService;
    }

    @PostConstruct
    void start() {
        // LOW.11：非法间隔钳制到 ≥1s，并记录生效值
        long intervalSec = Math.max(1L, advanceIntervalSec);
        if (advanceIntervalSec < 1L) {
            log.warn("flight-advance-interval-sec={} 非法（须≥1），钳制为 1s", advanceIntervalSec);
        }
        // 阶段一 17：接近半径兜底，避免配置为 0/负数导致永不切换
        if (!Double.isFinite(approachRadiusM) || approachRadiusM <= 0) {
            log.warn("approach-radius-m={} 非法，回落默认 {}m", approachRadiusM, DEFAULT_APPROACH_RADIUS_M);
            approachRadiusM = DEFAULT_APPROACH_RADIUS_M;
        }
        if (!advanceEnabled) {
            log.info("飞行阶段推进轮询未启用（wavenest.m2.flight-advance-enabled=false）");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("飞行阶段推进轮询已启动：生效间隔 {}s，接近半径 {}m", intervalSec, approachRadiusM);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 订单态变化后把该订单的周期缓存<b>标脏</b>（不删除）：重派可能复用同一 {@code meet_time}
     * 与执行机，不能只靠时间键识别新周期，故必须让下一拍重建；但直接删掉会让
     * {@link #activeOrderIdOf} 在「派发已提交、首拍未到」的窗口内返回空，
     * 前端实时出口的 {@code activeOrderId} 随之丢值。
     *
     * <p>派发事务提交后再标，避免标到未提交的旧行。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        String orderId = event.view().orderId();
        cycleStates.computeIfPresent(orderId, (key, state) -> state.markStale());
        contextRetryAfterMs.remove(orderId);
    }

    /**
     * 推进一拍：扫描在飞订单，按到期时刻补齐所有应发生的迁移。
     *
     * <p>公开供测试与运维手动触发；重复调用幂等，已记录的阶段不会重复写。
     */
    public synchronized void tick() {
        List<OrderTaskEntity> orders = orderTaskMapper.selectList(
                new LambdaQueryWrapper<OrderTaskEntity>()
                        .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES.stream()
                                .map(OrderStatus::getValue).toList())
                        .isNotNull(OrderTaskEntity::getMeetTime)
                        .isNotNull(OrderTaskEntity::getDroneId)
                        .isNotNull(OrderTaskEntity::getMeetLon)
                        .isNotNull(OrderTaskEntity::getMeetLat));
        for (OrderTaskEntity order : orders) {
            try {
                advanceOrder(order);
            } catch (Exception e) {
                log.warn("飞行阶段推进失败，跳过不影响其余：orderId={} err={}",
                        order.getOrderId(), e.getMessage());
            }
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("飞行阶段推进轮询异常（下一拍继续）：{}", e.getMessage());
        }
    }

    private void advanceOrder(OrderTaskEntity order) {
        long nowMs = clock.millis();
        long meetMs = toEpochMs(order.getMeetTime());
        String orderId = order.getOrderId();
        CycleState state = cycleStates.get(orderId);
        if (state == null || state.stale() || state.meetMs() != meetMs
                || !state.droneId().equals(order.getDroneId())) {
            // MEDIUM.10：resolve 失败后进入退避窗口，避免每拍重复查库
            Long retryAt = contextRetryAfterMs.get(orderId);
            if (retryAt != null && nowMs < retryAt) {
                return;
            }
            CyclePhase cycle = lastCyclePhase(orderId);
            OrderStatus phase = cycle == null ? OrderStatus.DISPATCHED : cycle.phase();
            DroneContext drone = droneContextPort.resolve(order.getDroneId());
            if (drone == null) {
                if (contextRetryAfterMs.size() >= WARN_KEY_CAPACITY) {
                // 只影响退避时长，清空至多多查一次库；不设上限则长期缺上下文的订单会一直堆积
                contextRetryAfterMs.clear();
            }
            contextRetryAfterMs.put(orderId, nowMs + CONTEXT_RETRY_BACKOFF_MS);
                warnMissingContext(orderId, meetMs, order.getDroneId());
                return;
            }
            contextRetryAfterMs.remove(orderId);
            warnedMissingContext.remove(warnKey(orderId, meetMs));
            CycleState rebuilt = CycleState.fresh(meetMs, order.getDroneId(), phase, drone,
                    auditFloorMs(order, cycle));
            // 用 compute 原子替换：并发线程若已写入更新的周期状态（未标脏、同一会面时刻），
            // 保留它，不拿重建结果覆盖——覆盖会把并发的阶段推进抹掉。
            state = cycleStates.compute(orderId, (key, current) ->
                    (current == null || current.stale() || current.meetMs() != meetMs)
                            ? rebuilt
                            : current);
            evictOldestCycleStateIfOverCapacity();
        }
        if (state.phase() == OrderStatus.FINISHED) {
            // HIGH.3：已完成订单不再保留周期缓存，避免三张 Map 无限增长
            evictOrder(orderId);
            return;
        }

        DroneContext drone = state.drone();
        double meetLon = order.getMeetLon().doubleValue();
        double meetLat = order.getMeetLat().doubleValue();
        double outboundM = GeoApprox.distanceM(
                drone.nestLon(), drone.nestLat(), meetLon, meetLat);
        int handoverSec = Math.max(0, sysParamLoader.getInt(HANDOVER_SEC_KEY, DEFAULT_HANDOVER_SEC));
        List<FlightPhaseTimeline.Transition> schedule = FlightPhaseTimeline.schedule(
                meetMs, outboundM, drone.cruiseSpeedMs(), handoverSec);

        // 11-05 序号 2：本周期「实际送达时刻」＝会面时刻 ＋ 交接时长，与 HANDOVER→RETURNING
        // 的迁移时刻同源，派发后即确定、不随时间漂移。飞中 ETA 另算，位置缺失时回落到它。
        long deliveredTsMs = OrderEta.dispatched(meetMs, handoverSec);
        double vMaxCruiseMs = sysParamLoader.getDouble(VMAX_CRUISE_KEY, DEFAULT_VMAX_CRUISE_MS);
        long inFlightEtaMs = outboundEtaMs(order, meetLon, meetLat, nowMs, vMaxCruiseMs,
                handoverSec, deliveredTsMs);

        // 阶段一 17：实时位置进入接近半径则提前切末段；位置不可用/超半径保持时间轴
        if (state.phase() == OrderStatus.OUTBOUND) {
            OptionalLong handoverTs = handoverTsByPosition(
                    order, meetLon, meetLat, nowMs, state.floorMs());
            if (handoverTs.isPresent()) {
                long tsMs = handoverTs.getAsLong();
                if (!emit(order, OrderStatus.OUTBOUND, OrderStatus.HANDOVER, "末段切入（位置）",
                        tsMs, deliveredTsMs)) {
                    return;
                }
                state = state.advanceTo(OrderStatus.HANDOVER, tsMs);
                cycleStates.put(orderId, state);
            }
        }

        for (FlightPhaseTimeline.Transition transition : schedule) {
            if (rank(state.phase()) >= rank(transition.to()) || nowMs < transition.dueMs()) {
                continue;
            }
            long tsMs = FlightPhaseTimeline.auditTs(transition.dueMs(), state.floorMs());
            // 起飞进「去程中」写飞中估计值；末段及之后写本周期实际送达值
            long etaTsMs = transition.to() == OrderStatus.OUTBOUND ? inFlightEtaMs : deliveredTsMs;
            if (!emit(order, transition.from(), transition.to(), transition.opType(), tsMs,
                    etaTsMs)) {
                // 订单态与预期不一致：本轮不推进，等下一拍重新判定
                return;
            }
            state = state.advanceTo(transition.to(), tsMs);
            cycleStates.put(orderId, state);
        }
        if (state.phase() == OrderStatus.FINISHED) {
            // 当拍即清：下一拍订单已不在飞、扫不到，留到那时就永远清不掉
            evictOrder(orderId);
        }
    }

    /**
     * 阶段一 17：{@code OUTBOUND} 阶段按无人机实时位置判定是否进入末段。
     *
     * <p>位置缺失（遥测未就绪）或距离大于接近半径时返回空，由调用方继续时间轴推进。
     *
     * @return 末段切入的审计时刻；不该切或切不了时为空
     */
    private OptionalLong handoverTsByPosition(OrderTaskEntity order,
            double meetLon, double meetLat, long nowMs, long floorMs) {
        if (dronePositionPort == null) {
            return OptionalLong.empty();
        }
        Optional<DronePositionPort.Position> position = dronePositionPort.latest(order.getDroneId());
        if (position.isEmpty()) {
            return OptionalLong.empty();
        }
        DronePositionPort.Position pos = position.get();
        if (GeoApprox.distanceM(pos.lon(), pos.lat(), meetLon, meetLat) > approachRadiusM) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(FlightPhaseTimeline.auditTs(nowMs, floorMs));
    }

    /**
     * 飞中预计送达时刻：{@code 当前时刻 + 剩余距离 ÷ vMaxCruise + 交接时长}。
     *
     * <p>剩余距离取「机当前位置→会面点」直线距离，与 {@link #handoverTsByPosition} 同一坐标口径。
     * 位置缺失（遥测未就绪）时返回 {@code fallbackTsMs}（本周期实际送达时刻），
     * <b>不抛异常、不臆造位置</b>——缺位置只该让 ETA 退化成已知的确定值。
     *
     * @param order         订单（取机号）
     * @param meetLon       会面点经度
     * @param meetLat       会面点纬度
     * @param nowMs         当前时刻，Unix 毫秒
     * @param vMaxCruiseMs  最大巡航速度，m/s
     * @param handoverSec   交接时长，秒
     * @param fallbackTsMs  位置不可用时的回落值，Unix 毫秒
     * @return 飞中预计送达时刻，Unix 毫秒
     */
    private long outboundEtaMs(OrderTaskEntity order, double meetLon, double meetLat, long nowMs,
            double vMaxCruiseMs, int handoverSec, long fallbackTsMs) {
        if (dronePositionPort == null) {
            return fallbackTsMs;
        }
        Optional<DronePositionPort.Position> position = dronePositionPort.latest(order.getDroneId());
        if (position.isEmpty()) {
            return fallbackTsMs;
        }
        DronePositionPort.Position pos = position.get();
        double remainingM = GeoApprox.distanceM(pos.lon(), pos.lat(), meetLon, meetLat);
        return OrderEta.inFlightOr(nowMs, remainingM, vMaxCruiseMs, handoverSec, fallbackTsMs);
    }

    /**
     * 按机号查当前在飞订单号（T1-2：实时出口投影用它填 {@code DroneStateFrame.activeOrderId}）。
     *
     * <p>取 {@link #cycleStates} 中机号相同、且尚未推进到「已完成」的订单，多条时取
     * {@code meet_time} 最新的一条——重入队后 {@code create_time} 不变，只有
     * {@code meet_time} 才标识本派发周期。
     *
     * <p><b>为什么不查库</b>：{@link #cycleStates} 的 {@code phase} 是由 {@code task_log}
     * 恢复的真实阶段，判得准；库里的订单态可能因派发时序短暂滞后。
     *
     * <p><b>两条边界</b>：① 缓存由 {@code tick()} 按
     * {@code wavenest.m2.flight-advance-interval-sec}（默认 2 秒）刷新，派单后到首次 tick
     * 之前取不到值；② 缺执行机上下文的订单（{@code droneContextPort.resolve} 返回 null）
     * 在 {@link #advanceOrder} 中直接 return、不建 state，该机永远查不到，与候选链同一数据前提。
     *
     * @return 在飞订单号；该机当前无在飞订单时返回 {@code null}（契约允许为空，不算缺值）
     */
    public String activeOrderIdOf(String droneId) {
        if (droneId == null) {
            return null;
        }
        String activeOrderId = null;
        long latestMeetMs = Long.MIN_VALUE;
        for (Map.Entry<String, CycleState> entry : cycleStates.entrySet()) {
            CycleState state = entry.getValue();
            if (!droneId.equals(state.droneId()) || state.phase() == OrderStatus.FINISHED) {
                continue;
            }
            if (state.meetMs() > latestMeetMs) {
                latestMeetMs = state.meetMs();
                activeOrderId = entry.getKey();
            }
        }
        return activeOrderId;
    }

    /**
     * 当前缓存的在飞订单数（运维观察口径）。
     *
     * <p>订单推进到「已完成」或建成即完成时会即时淘汰，本值不应随时间单调增长；
     * 若持续增长说明淘汰链失效（HIGH.3）。
     */
    int trackedCycleCount() {
        return cycleStates.size();
    }

    /**
     * 按状态链顺序校验并写入一条飞行态审计，随后广播展示视图。
     *
     * <p><b>阶段一 23</b>：{@code task_log}、{@code order_task.status}、{@code drone} 状态
     * 在同一事务内写入；订单条件更新影响 0 行时记 error 并<b>不写审计</b>、返回 {@code false}，
     * 由调用方跳过本轮推进。
     *
     * <p><b>MEDIUM.63</b>：先改订单态、再插 {@code task_log}、最后改无人机状态（条件更新），
     * 保证审计先于副作用落库。
     *
     * @param tsMs 审计时刻，调用方须已用 {@link FlightPhaseTimeline#auditTs} 钳制过，
     *             保证同订单严格递增且不早于下单时刻
     * @param etaTsMs 本次应写入的预计送达时刻，Unix 毫秒；与库中已有值取较大者落库
     *                （单调收敛，见 {@link OrderEta#notEarlierThan}）
     * @return 是否真正完成本次迁移写入
     */
    private boolean emit(OrderTaskEntity order, OrderStatus from, OrderStatus to,
            String opType, long tsMs, long etaTsMs) {
        if (rank(to) != rank(from) + 1) {
            throw new IllegalStateException("飞行阶段流转不连续：" + from.getValue() + " → " + to.getValue());
        }
        Long previousEtaTs = order.getEtaTs() == null ? null : toEpochMs(order.getEtaTs());
        // 末段及之后写的是「实际送达」，是本周期唯一确定的值，必须如实覆盖——
        // 若也取较大者，一旦下单下界偏晚（下界是保守估计，可能晚于真实送达），
        // 库里就会永久留下一个错的送达时刻。
        // 去程中写的是估计值，与已发布值取较大者，避免位置抖动让前端展示回跳。
        long effectiveEtaTs = rank(to) >= rank(OrderStatus.HANDOVER)
                ? etaTsMs
                : OrderEta.notEarlierThan(etaTsMs, previousEtaTs);
        LocalDateTime etaDb = LocalDateTime.ofInstant(Instant.ofEpochMilli(effectiveEtaTs), DB_ZONE);
        boolean[] written = {false};
        runInTransaction(() -> {
            int rows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                    .eq(OrderTaskEntity::getOrderId, order.getOrderId())
                    .eq(OrderTaskEntity::getStatus, from.getValue())
                    .set(OrderTaskEntity::getStatus, to.getValue())
                    // 11-05 序号 2：ETA 与状态在同一条件更新里落库，不会出现
                    // 「状态已推进、ETA 还是上一阶段值」的中间态
                    .set(OrderTaskEntity::getEtaTs, etaDb));
            if (rows == 0) {
                log.error("订单态与飞行阶段不一致，跳过本次审计：orderId={} 期望 {} → {}",
                        order.getOrderId(), from.getValue(), to.getValue());
                return;
            }

            TaskLogEntity entry = new TaskLogEntity();
            entry.setOrderId(order.getOrderId());
            entry.setOpType(opType);
            entry.setFromStatus(from.getValue());
            entry.setToStatus(to.getValue());
            entry.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), DB_ZONE));
            taskLogMapper.insert(entry);

            syncDroneStatus(order.getDroneId(), to);

            long version = entry.getLogId() != null
                    ? Math.max(2L, entry.getLogId() + 1L)
                    : fallbackVersions.computeIfAbsent(order.getOrderId(), ignored -> new AtomicLong(1L))
                            .incrementAndGet();
            try {
                eventPublisher.publishEvent(new FlightPhaseChangedEvent(new OrderView(
                        order.getOrderId(), to, tsMs, version, null, null, null, null,
                        effectiveEtaTs)));
            } catch (Exception e) {
                // 审计已落库，推送失败只告警；下一拍不会重发同一阶段，避免重复刷屏
                log.warn("飞行阶段广播失败（task_log 已落库）：orderId={} status={} err={}",
                        order.getOrderId(), to.getValue(), e.getMessage());
            }
            written[0] = true;
        });
        if (written[0]) {
            order.setStatus(to.getValue());
            order.setEtaTs(etaDb);
            log.info("飞行阶段推进：orderId={} {} → {} op={} ts={} etaTs={}",
                    order.getOrderId(), from.getValue(), to.getValue(), opType, tsMs,
                    effectiveEtaTs);
            // C3：订单到达「已完成」终态时释放迫停占位（事务外执行，释放失败不回滚订单态）
            if (to == OrderStatus.FINISHED && occupancySlotService != null) {
                occupancySlotService.releaseByOrder(order.getOrderId());
            }
        }
        return written[0];
    }

    /** 在事务模板存在时包裹执行；测试无 Spring 事务时退化为直接执行。 */
    private void runInTransaction(Runnable action) {
        TransactionTemplate template = this.transactionTemplate;
        if (template == null) {
            action.run();
            return;
        }
        template.execute(status -> {
            action.run();
            return null;
        });
    }

    /**
     * 无人机状态按 23 §7 同步推进（MEDIUM.63：条件更新，不做整行覆盖）。
     *
     * <p>在 {@code task_log} 落库之后调用；{@code HANDOVER} 不改无人机状态。
     */
    private void syncDroneStatus(String droneId, OrderStatus to) {
        if (droneMapper == null || droneId == null) {
            return;
        }
        LambdaUpdateWrapper<DroneEntity> update = new LambdaUpdateWrapper<DroneEntity>()
                .eq(DroneEntity::getDroneId, droneId);
        switch (to) {
            case OUTBOUND -> update
                    // 只接「本单该接的」机：已被别的单占住或在飞的，不覆盖
                    // （条件更新 + 影响行数；本批禁改表结构，drone 表无订单列）。
                    .in(DroneEntity::getStatus, "空闲", "已预占")
                    .set(DroneEntity::getStatus, "执行任务中")
                    .set(DroneEntity::getPositionType, "空中")
                    .set(DroneEntity::getCurrentNestId, null);
            case RETURNING -> update
                    .eq(DroneEntity::getStatus, "执行任务中")
                    .set(DroneEntity::getStatus, "返航中")
                    .set(DroneEntity::getPositionType, "空中");
            case FINISHED -> {
                DroneEntity drone = droneMapper.selectById(droneId);
                if (drone == null) {
                    return;
                }
                update.in(DroneEntity::getStatus, "执行任务中", "返航中")
                        .set(DroneEntity::getStatus, "空闲")
                        .set(DroneEntity::getPositionType, "原巢")
                        .set(DroneEntity::getCurrentNestId, drone.getHomeNestId());
            }
            default -> {
                return; // 末段交接中（HANDOVER）等不回写
            }
        }
        int affected = droneMapper.update(null, update);
        if (affected == 0) {
            log.warn("无人机状态未更新（当前状态不在本单可接范围）：droneId={} 目标状态={}",
                    droneId, to.getValue());
        }
    }

    /**
     * 从 task_log 取本派发周期已发生的最后一段飞行态迁移（阶段＋审计时刻）。
     *
     * <p>只认飞行态四值，订单受理链的日志不得被误当飞行阶段。
     *
     * <p><b>MEDIUM.64</b>：以「派发」日志为周期边界；边界缺失时记 warn 并返回 {@code null}，
     * 不再退化为「不加过滤地取历史飞行日志」（那会把上一周期串进来）。
     *
     * <p>同时带回该条日志的时刻，作为后续审计时刻的下界——这是「时间不倒挂」的锚点：
     * 重建后写下的第一条审计必须晚于本周期已有的最后一条。
     *
     * @return 阶段与审计时刻；本周期尚无飞行日志时阶段为「已派发」、时刻为派发时刻；
     *         周期边界缺失时 {@code null}
     */
    private CyclePhase lastCyclePhase(String orderId) {
        TaskLogEntity lastDispatch = taskLogMapper.selectOne(
                new LambdaQueryWrapper<TaskLogEntity>()
                        .eq(TaskLogEntity::getOrderId, orderId)
                        .eq(TaskLogEntity::getOpType, DISPATCH_OP_TYPE)
                        .orderByDesc(TaskLogEntity::getLogId)
                        .last("LIMIT 1"));
        if (lastDispatch == null || lastDispatch.getLogId() == null) {
            log.warn("周期边界缺失（无派发日志），本轮不按历史飞行日志推进：orderId={}", orderId);
            return null;
        }
        long dispatchMs = toEpochMs(lastDispatch.getTs());
        LambdaQueryWrapper<TaskLogEntity> query = new LambdaQueryWrapper<TaskLogEntity>()
                .eq(TaskLogEntity::getOrderId, orderId)
                .in(TaskLogEntity::getToStatus, FLIGHT_PHASES.stream()
                        .map(OrderStatus::getValue).toList())
                .gt(TaskLogEntity::getLogId, lastDispatch.getLogId())
                .orderByDesc(TaskLogEntity::getLogId)
                .last("LIMIT 1");
        List<TaskLogEntity> logs = taskLogMapper.selectList(query);
        if (logs == null || logs.isEmpty()) {
            return new CyclePhase(OrderStatus.DISPATCHED, dispatchMs);
        }
        TaskLogEntity last = logs.get(0);
        String value = last.getToStatus();
        return FLIGHT_PHASES.stream()
                .filter(status -> status.getValue().equals(value))
                .findFirst()
                .map(status -> new CyclePhase(status, toEpochMs(last.getTs())))
                .orElse(new CyclePhase(OrderStatus.DISPATCHED, dispatchMs));
    }

    /** 审计时刻下界：下单时刻与本周期已有审计取大者，保证任务日志不早于下单时刻。 */
    private static long auditFloorMs(OrderTaskEntity order, CyclePhase cycle) {
        long floorMs = toEpochMs(order.getCreateTime());
        return cycle == null ? floorMs : Math.max(floorMs, cycle.floorMs());
    }

    /** 订单已完成：清理该订单的全部进程内缓存（HIGH.3）。 */
    private void evictOrder(String orderId) {
        cycleStates.remove(orderId);
        fallbackVersions.remove(orderId);
        contextRetryAfterMs.remove(orderId);
        warnedMissingContext.removeIf(key -> key.startsWith(orderId + "@"));
    }

    /**
     * 周期缓存超容量时淘汰审计时刻最旧的一条（HIGH-PERF.3）。
     *
     * <p><b>为什么原来只按「已完成」淘汰不够</b>：长期停在中间态的订单（如告警挂起、
     * 迫停待回收）不会走到 {@code FINISHED}，周期缓存就只增不减。
     *
     * <p><b>为什么按 {@code floorMs} 最小者淘汰</b>：{@code floorMs} 是本周期最后一条审计的时刻，
     * 它最小的就是最久没有推进的那个；淘汰它对正在推进的订单影响最小。
     */
    private void evictOldestCycleStateIfOverCapacity() {
        if (cycleStates.size() <= CYCLE_STATE_CAPACITY) {
            return;
        }
        String oldestKey = null;
        long oldestFloorMs = Long.MAX_VALUE;
        for (Map.Entry<String, CycleState> entry : cycleStates.entrySet()) {
            if (entry.getValue().floorMs() < oldestFloorMs) {
                oldestFloorMs = entry.getValue().floorMs();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            evictOrder(oldestKey);
        }
    }

    private void warnMissingContext(String orderId, long meetMs, String droneId) {
        if (warnedMissingContext.size() >= WARN_KEY_CAPACITY) {
            warnedMissingContext.clear();
        }
        if (warnedMissingContext.add(warnKey(orderId, meetMs))) {
            log.warn("飞行阶段推进缺执行机上下文，跳过：orderId={} droneId={}", orderId, droneId);
        }
    }

    private static String warnKey(String orderId, long meetMs) {
        return orderId + "@" + meetMs;
    }

    private static int rank(OrderStatus status) {
        return switch (status) {
            case DISPATCHED -> 0;
            case OUTBOUND -> 1;
            case HANDOVER -> 2;
            case RETURNING -> 3;
            case FINISHED -> 4;
            default -> -1;
        };
    }

    private static long toEpochMs(LocalDateTime time) {
        return time == null ? 0L : time.atZone(DB_ZONE).toInstant().toEpochMilli();
    }

    /** task_log 落库时刻；缺失按 0 处理，由调用方与下单时刻取大者兜底。 */
    private static long toEpochMs(TaskLogEntity log) {
        return toEpochMs(log.getTs());
    }

    /** 一次派发周期在库里的落点：当前阶段＋该阶段审计时刻（MEDIUM.64 的周期边界）。 */
    private record CyclePhase(OrderStatus phase, long floorMs) {
    }

    /**
     * 一次派发周期内的缓存。
     *
     * <p>{@code floorMs} 是本周期最后一条审计的时刻，用于钳制下一次写入，保证
     * {@code task_log.ts} 严格递增；{@code stale} 表示订单态已变化、下一拍须重建
     * （重建前仍留在缓存里，供实时出口继续取到该机在飞订单）。
     */
    private record CycleState(long meetMs, String droneId, OrderStatus phase, DroneContext drone,
            long floorMs, boolean stale) {

        /** 按库里的真实落点新建一个可用周期。 */
        static CycleState fresh(long meetMs, String droneId, OrderStatus phase, DroneContext drone,
                long floorMs) {
            return new CycleState(meetMs, droneId, phase, drone, floorMs, false);
        }

        /** 推进到下一阶段，并把审计下界前移到本次写入时刻。 */
        CycleState advanceTo(OrderStatus next, long emittedMs) {
            return new CycleState(meetMs, droneId, next, drone, emittedMs, false);
        }

        /** 标记待重建；已标记则原样返回，避免重复建对象。 */
        CycleState markStale() {
            return stale ? this : new CycleState(meetMs, droneId, phase, drone, floorMs, true);
        }
    }
}
