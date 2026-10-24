package com.waterlogistics.dispatch.taskstate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 飞行阶段推进器（F2）——按时间把在飞订单推进
 * {@code 已派发 → 去程中 → 末段交接中 → 返航中 → 已完成}。
 *
 * <p><b>写入口径</b>：飞行态只写 {@code task_log}，事实来源是已派发订单的
 * {@code meet_time}（会面时刻）与机型巡航速度。<b>不写 {@code order_task.status}</b>：
 * 那是订单态，飞行态由本推进器在事件流中表达，避免两套状态互相覆盖。
 *
 * <p><b>时间基准</b>：
 * <ul>
 *   <li>去程时长＝机巢到会面点的大圆距离 ÷ 巡航速度；</li>
 *   <li>起飞时刻＝会面时刻 − 去程时长；</li>
 *   <li>末段交接开始＝会面时刻；</li>
 *   <li>返航开始＝会面时刻 ＋ {@code sys_param.tHandoverMinSec}；</li>
 *   <li>已完成＝返航开始 ＋ 去程时长（返程按同一机型速度回原巢）。</li>
 * </ul>
 *
 * <p><b>为什么可以按时间推进</b>：演示期遥测是绕圈仿真，按「进入会面点邻域」
 * 这类位置条件不会稳定触发；会面侧已有的交接判定仍是正式口径，接入样本流后
 * 直接复用其结论替换本推进器的对应时刻，不在本类重算交接判定。
 *
 * <p><b>广播</b>：每次迁移写一条 {@code task_log}（{@code from_status}、
 * {@code to_status}、{@code op_type} 齐全），并发布 {@link FlightPhaseChangedEvent}，
 * 由组装层转成 {@code order_state}；晚到的拍会按到期顺序补记，保证事件流完整。
 */
@Component
public class FlightPhaseAdvancer {

    private static final Logger log = LoggerFactory.getLogger(FlightPhaseAdvancer.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 交接时长下界（秒）：交接完成时刻与会面时刻之差，走 sys_param，不硬编码 */
    private static final String HANDOVER_SEC_KEY = "tHandoverMinSec";

    private static final int DEFAULT_HANDOVER_SEC = 120;

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
    private final SysParamLoader sysParamLoader;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Map<String, AtomicLong> fallbackVersions = new ConcurrentHashMap<>();

    /** 当前派发周期的阶段缓存：meet_time 或 droneId 变化即作废，避免每拍重复查库。 */
    private final Map<String, CycleState> cycleStates = new ConcurrentHashMap<>();

    /** 缺执行机上下文的告警去重键：orderId@meetMs，每周期只告警一次。 */
    private final Set<String> warnedMissingContext = ConcurrentHashMap.newKeySet();

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
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher) {
        this(orderTaskMapper, taskLogMapper, droneContextPort, sysParamLoader,
                eventPublisher, Clock.systemDefaultZone());
    }

    FlightPhaseAdvancer(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneContextPort droneContextPort,
            SysParamLoader sysParamLoader,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.droneContextPort = droneContextPort;
        this.sysParamLoader = sysParamLoader;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        if (!advanceEnabled) {
            log.info("飞行阶段推进轮询未启用（wavenest.m2.flight-advance-enabled=false）");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely,
                advanceIntervalSec, advanceIntervalSec, TimeUnit.SECONDS);
        log.info("飞行阶段推进轮询已启动：间隔 {}s", advanceIntervalSec);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 订单态变化后作废该订单的周期缓存：重派可能复用同一 meet_time 与执行机，
     * 不能只靠时间键识别新周期；派发事务提交后再清，避免清到未提交的旧行。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        cycleStates.remove(event.view().orderId());
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
        long meetMs = toEpochMs(order.getMeetTime());
        CycleState state = cycleStates.get(order.getOrderId());
        if (state == null || state.meetMs() != meetMs
                || !state.droneId().equals(order.getDroneId())) {
            OrderStatus phase = lastFlightPhase(order.getOrderId());
            if (phase == null) {
                phase = OrderStatus.DISPATCHED;
            }
            DroneContext drone = droneContextPort.resolve(order.getDroneId());
            if (drone == null) {
                String warnKey = order.getOrderId() + "@" + meetMs;
                if (warnedMissingContext.add(warnKey)) {
                    log.warn("飞行阶段推进缺执行机上下文，跳过：orderId={} droneId={}",
                            order.getOrderId(), order.getDroneId());
                }
                return;
            }
            warnedMissingContext.remove(order.getOrderId() + "@" + meetMs);
            state = new CycleState(meetMs, order.getDroneId(), phase, drone);
            cycleStates.put(order.getOrderId(), state);
        }
        if (state.phase() == OrderStatus.FINISHED) {
            return;
        }

        DroneContext drone = state.drone();
        double outboundM = GeoApprox.distanceM(
                drone.nestLon(), drone.nestLat(),
                order.getMeetLon().doubleValue(), order.getMeetLat().doubleValue());
        int handoverSec = Math.max(0, sysParamLoader.getInt(HANDOVER_SEC_KEY, DEFAULT_HANDOVER_SEC));
        List<FlightPhaseTimeline.Transition> schedule = FlightPhaseTimeline.schedule(
                meetMs, outboundM, drone.cruiseSpeedMs(), handoverSec);

        long nowMs = clock.millis();
        for (FlightPhaseTimeline.Transition transition : schedule) {
            if (rank(state.phase()) >= rank(transition.to()) || nowMs < transition.dueMs()) {
                continue;
            }
            emit(order, transition.from(), transition.to(), transition.opType(), transition.dueMs());
            state = new CycleState(meetMs, state.droneId(), transition.to(), drone);
            cycleStates.put(order.getOrderId(), state);
        }
    }

    /**
     * 按状态链顺序校验并写入一条飞行态审计，随后广播展示视图。
     */
    private void emit(OrderTaskEntity order, OrderStatus from, OrderStatus to,
            String opType, long tsMs) {
        if (rank(to) != rank(from) + 1) {
            throw new IllegalStateException("飞行阶段流转不连续：" + from.getValue() + " → " + to.getValue());
        }
        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(order.getOrderId());
        entry.setOpType(opType);
        entry.setFromStatus(from.getValue());
        entry.setToStatus(to.getValue());
        entry.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), DB_ZONE));
        taskLogMapper.insert(entry);

        long version = entry.getLogId() != null
                ? Math.max(2L, entry.getLogId() + 1L)
                : fallbackVersions.computeIfAbsent(order.getOrderId(), ignored -> new AtomicLong(1L))
                        .incrementAndGet();
        try {
            eventPublisher.publishEvent(new FlightPhaseChangedEvent(new OrderView(
                    order.getOrderId(), to, tsMs, version, null, null, null, null)));
        } catch (Exception e) {
            // 审计已落库，推送失败只告警；下一拍不会重发同一阶段，避免重复刷屏
            log.warn("飞行阶段广播失败（task_log 已落库）：orderId={} status={} err={}",
                    order.getOrderId(), to.getValue(), e.getMessage());
        }
        log.info("飞行阶段推进：orderId={} {} → {} op={} ts={}",
                order.getOrderId(), from.getValue(), to.getValue(), opType, tsMs);
    }

    /**
     * 从 task_log 取该订单最近一次飞行态迁移；无记录时返回 {@code null}。
     * 只认飞行态四值，订单受理链的日志不得被误当飞行阶段。
     */
    private OrderStatus lastFlightPhase(String orderId) {
        TaskLogEntity lastDispatch = taskLogMapper.selectOne(
                new LambdaQueryWrapper<TaskLogEntity>()
                        .eq(TaskLogEntity::getOrderId, orderId)
                        .eq(TaskLogEntity::getOpType, DISPATCH_OP_TYPE)
                        .orderByDesc(TaskLogEntity::getLogId)
                        .last("LIMIT 1"));
        LambdaQueryWrapper<TaskLogEntity> query = new LambdaQueryWrapper<TaskLogEntity>()
                .eq(TaskLogEntity::getOrderId, orderId)
                .in(TaskLogEntity::getToStatus, FLIGHT_PHASES.stream()
                        .map(OrderStatus::getValue).toList());
        if (lastDispatch != null && lastDispatch.getLogId() != null) {
            // 重派会复用同一 orderId：只认本次「派发」之后的飞行日志，避免上一周期串线
            query.gt(TaskLogEntity::getLogId, lastDispatch.getLogId());
        }
        List<TaskLogEntity> logs = taskLogMapper.selectList(
                query.orderByDesc(TaskLogEntity::getLogId).last("LIMIT 1"));
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        String value = logs.get(0).getToStatus();
        for (OrderStatus status : FLIGHT_PHASES) {
            if (status.getValue().equals(value)) {
                return status;
            }
        }
        return null;
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
        return time.atZone(DB_ZONE).toInstant().toEpochMilli();
    }

    /** 一次派发周期内的缓存，meet_time 或执行机变化即视为新周期。 */
    private record CycleState(long meetMs, String droneId, OrderStatus phase, DroneContext drone) {
    }
}
