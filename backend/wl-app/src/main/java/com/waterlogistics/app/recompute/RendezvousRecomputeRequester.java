package com.waterlogistics.app.recompute;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.ShipState;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.dispatch.frame.RendezvousFrameProjector;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.dispatch.prediction.RecomputeDecisionGate;
import com.waterlogistics.dispatch.prediction.RecomputeRequest;
import com.waterlogistics.dispatch.prediction.RecomputeRequester;
import com.waterlogistics.dispatch.prediction.ReplanDecisionExpiredEvent;
import com.waterlogistics.dispatch.rendezvous.ManualMeetRecomputePort;
import com.waterlogistics.dispatch.rendezvous.ManualMeetRecomputePort.Outcome;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.dispatch.weather.WeatherSnapshotPort;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.solver.RecalcOptions;
import com.waterlogistics.rendezvous.solver.RecalcStateSnapshot;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcRequest;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcResult;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 六a 纠偏接会面点（阶段一 15）——把「航向跳变 ≥20°」的重算信号接到 M3 会面点重算。
 *
 * <p><b>链路</b>：{@link PredictionService#recompute(String)} 刷新快照
 * → 按 {@code ship_mmsi} 查在飞订单 → {@link RendezvousFacade#recompute} 重解会面点
 * → 条件更新 {@code order_task.meet_lon/meet_lat}
 * → 发布 {@link RendezvousPlanCommittedEvent}（由 {@code RealtimeEventBridge} 转 {@code rendezvous} 帧）。
 *
 * <p><b>口径</b>：重算请求只带船侧状态与期望快照版本，执行机起点由 M3 按 {@code droneId}
 * 反查当前所在巢；重算只算会面点，不重算航迹（量判分离）。
 *
 * <p><b>只更新坐标、不动 {@code meet_time}</b>：会面点微调不应触发
 * {@code FlightPhaseAdvancer} 重建派发周期时间轴；末段切入已由任务 17 的位置判定兜底。
 *
 * <p><b>失败语义</b>：求解/快照缺失一律保留旧点，不向上抛（本方法在预测刷新热路径上，
 * 下游不可用不得带停预测链）；失败告警按订单节流，避免每个 AIS 刷新刷屏。
 *
 * <p><b>装配</b>：{@code @Primary} 优先于默认实现 {@code LoggingRecomputeRequester}，
 * 见 {@link RecomputeRequester} 的装配规则。
 *
 * <p><b>序号 3 追加</b>：本类同时实现 {@link ManualMeetRecomputePort}，为人工
 * 「调整会面点」（《1112》§3.2）提供「按最新预测重算」的出口——请求体不带坐标，
 * 会面点由 M3 门面按当前快照重解；人工指令不走 6a 六键判定，只守会面决策窗口。
 */
@Component
@Primary
public class RendezvousRecomputeRequester implements RecomputeRequester, ManualMeetRecomputePort {

    private static final Logger log = LoggerFactory.getLogger(RendezvousRecomputeRequester.class);

    /** 重算只针对在飞订单：「返航中」及之后会面点已无意义。 */
    private static final List<String> IN_FLIGHT_STATUSES = List.of(
            OrderStatus.DISPATCHED.getValue(),
            OrderStatus.OUTBOUND.getValue(),
            OrderStatus.HANDOVER.getValue());

    /** 同一订单的失败告警节流窗口，毫秒。 */
    private static final long WARN_THROTTLE_MS = 60_000L;

    /** 节流表容量上限，超限整体清空（只用于告警去重，清空无业务影响）。 */
    private static final int WARN_THROTTLE_CAPACITY = 1_000;

    private static final String HORIZON_SEC_KEY = "rendezvousHorizonSec";
    private static final String WINDOW_MARGIN_SEC_KEY = "rendezvousWindowMarginSec";
    private static final String EPSILON_SEC_KEY = "rendezvousEpsilonSec";
    private static final String MAX_ITER_KEY = "rendezvousMaxIter";
    private static final String WIND_SPEED_KEY = "windSpeedMs";
    private static final String WIND_DIR_DEG_KEY = "windDirDeg";

    private static final int DEFAULT_HORIZON_SEC = 1800;
    private static final int DEFAULT_WINDOW_MARGIN_SEC = 120;
    private static final double DEFAULT_EPSILON_SEC = 1.0;
    private static final int DEFAULT_MAX_ITER = 50;

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final PredictionService predictionService;
    private final OrderTaskMapper orderTaskMapper;
    private final RendezvousFacade rendezvousFacade;
    private final DroneContextPort droneContextPort;
    private final WeatherSnapshotPort weatherSnapshotPort;
    private final SysParamLoader sysParamLoader;
    private final RecomputeDecisionGate decisionGate;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /** orderId → 最近一次告警时刻（毫秒），仅用于节流。 */
    private final Map<String, Long> lastWarnMs = new ConcurrentHashMap<>();

    /**
     * @param predictionService   预测服务（{@code @Lazy} 打断“预测 ↔ 重算”构造环）
     * @param orderTaskMapper     订单表
     * @param rendezvousFacade    会面门面（{@code @Lazy} 断开
     *                            15 → Facade → TrajectorySnapshotAdapter → PredictionService → 15 的环）
     * @param droneContextPort    执行机台账端口（重算按 {@code droneId} 反查起点与机型速度）
     * @param weatherSnapshotPort 代表风况来源（重算与首派同一取数口径）
     * @param sysParamLoader      重算时限与阈值参数
     * @param decisionGate        6a 判据闸门（八键读表、冷却期、迟滞、死区、决策截止）
     * @param eventPublisher      发布已提交会面方案
     * @param transactionTemplate 同事务提交「坐标更新 + 事件」，避免推送早于落库
     */
    public RendezvousRecomputeRequester(@Lazy PredictionService predictionService,
            OrderTaskMapper orderTaskMapper,
            @Lazy RendezvousFacade rendezvousFacade,
            DroneContextPort droneContextPort,
            WeatherSnapshotPort weatherSnapshotPort,
            SysParamLoader sysParamLoader,
            RecomputeDecisionGate decisionGate,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this.predictionService = predictionService;
        this.orderTaskMapper = orderTaskMapper;
        this.rendezvousFacade = rendezvousFacade;
        this.droneContextPort = droneContextPort;
        this.weatherSnapshotPort = weatherSnapshotPort;
        this.sysParamLoader = sysParamLoader;
        this.decisionGate = decisionGate;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void requestRecompute(RecomputeRequest request) {
        if (request == null) {
            return;
        }
        try {
            Optional<PredictionSnapshot> snapshot = predictionService.recompute(request.mmsi());
            if (snapshot.isEmpty()) {
                log.warn("会面重算跳过：mmsi={} 尚无可用观测", request.mmsi());
                return;
            }
            List<OrderTaskEntity> orders = inFlightOrders(request.mmsi());
            if (orders.isEmpty()) {
                log.debug("会面重算跳过：mmsi={} 无在飞订单", request.mmsi());
                return;
            }
            long nowMs = System.currentTimeMillis();
            for (OrderTaskEntity order : orders) {
                // 6a 判据闸门接每帧循环体：八键读表，冷却／迟滞／死区／最小间隔／决策截止
                // 全部在重算与下发之前判定；不通过的一律不调门面、不改会面点。
                RecomputeDecisionGate.Decision decision = decisionGate.evaluate(
                        order.getOrderId(), request.courseDeltaDeg(), order.getMeetTime(), nowMs);
                if (decision == RecomputeDecisionGate.Decision.DECISION_DEADLINE) {
                    decisionGate.noteDeadlineReport();
                    long meetMs = order.getMeetTime() == null ? 0L
                            : order.getMeetTime().atZone(DB_ZONE).toInstant().toEpochMilli();
                    eventPublisher.publishEvent(new ReplanDecisionExpiredEvent(
                            order.getOrderId(), request.mmsi(), meetMs, nowMs,
                            request.courseDeltaDeg(), "已进会面决策窗口，停止重算与下发"));
                    warnThrottled(order.getOrderId(),
                            "6a 决策截止，只上报 M2：orderId={} deltaDeg={}",
                            order.getOrderId(), request.courseDeltaDeg());
                    continue;
                }
                if (decision != RecomputeDecisionGate.Decision.ALLOW) {
                    log.debug("6a 重算跳过：orderId={} 判据={} deltaDeg={}",
                            order.getOrderId(), decision, request.courseDeltaDeg());
                    continue;
                }
                decisionGate.markTriggered(order.getOrderId(), nowMs);
                boolean committed = recomputeOne(order, snapshot.get(),
                        request.currentCourseDeg(), request.triggerReason());
                if (committed) {
                    decisionGate.markCompleted(order.getOrderId(), System.currentTimeMillis());
                }
            }
        } catch (RuntimeException e) {
            // 预测刷新热路径：失败只告警，不影响预测链本身
            log.warn("会面重算失败，保留旧会面点：mmsi={} err={}", request.mmsi(), e.getMessage());
        }
    }

    /**
     * 人工「调整会面点」：按订单当前最新预测重算一次会面点（《1112》§3.2）。
     *
     * <p><b>不走 6a 六键</b>：冷却期、最小间隔、迟滞、死区都是抑制「同一段变向反复触发」的
     * 自动判据；人工点击是一次显式指令，按六键判会把人拦在死区里（无航向跳变时
     * {@code deltaDeg} 恒为 0）。此处只守会面决策窗口 {@code rendezvousWindowMarginSec}——
     * 已进窗口就不再挪点，与 6a 同一键、同一口径。
     *
     * <p><b>成功登记冷却起点</b>：人工调完 {@link RecomputeDecisionGate#markCompleted} 一次，
     * 避免紧接着的自动重算立刻把人工结果覆盖掉。
     *
     * <p><b>异常不外抛</b>：本方法由 HTTP 干预链调用，失败用 {@link Outcome} 回吐原因，
     * 由上层决定回吐前端；会面点未改成功时既不推帧也不改 {@code meet_time}。
     */
    @Override
    public Outcome recomputeForOrder(String orderId, String triggerReason) {
        if (orderId == null || orderId.isBlank()) {
            return Outcome.FAILED;
        }
        OrderTaskEntity order;
        try {
            order = orderTaskMapper.selectById(orderId);
        } catch (RuntimeException e) {
            log.warn("人工调整会面点查单失败：orderId={} err={}", orderId, e.getMessage());
            return Outcome.FAILED;
        }
        if (order == null) {
            return Outcome.FAILED;
        }
        String droneId = order.getDroneId();
        if (!IN_FLIGHT_STATUSES.contains(order.getStatus()) || droneId == null || droneId.isBlank()) {
            return Outcome.NOT_DISPATCHED;
        }
        long nowMs = System.currentTimeMillis();
        LocalDateTime meetTime = order.getMeetTime();
        if (meetTime != null) {
            long meetMs = meetTime.atZone(DB_ZONE).toInstant().toEpochMilli();
            if (meetMs - nowMs <= (long) decisionGate.params().decisionDeadlineSec() * 1000L) {
                return Outcome.DEADLINE;
            }
        }
        Optional<PredictionSnapshot> snapshot;
        try {
            snapshot = predictionService.recompute(order.getShipMmsi());
        } catch (RuntimeException e) {
            log.warn("人工调整会面点刷新快照失败：orderId={} err={}", orderId, e.getMessage());
            return Outcome.FAILED;
        }
        if (snapshot.isEmpty()) {
            return Outcome.NO_SNAPSHOT;
        }
        // 人工指令没有航向跳变，观测航向传 NaN，由 buildRequest 回落快照主轴方位。
        RendezvousRecalcResult result = recomputeWithRetry(order, snapshot.get(), droneId,
                Double.NaN, triggerReason);
        if (result == null) {
            log.warn("人工调整会面点求解失败，保留旧点：orderId={}", orderId);
            return Outcome.FAILED;
        }
        B_RendezvousSolution solution = result.solution();
        if (!solution.hasSolution()) {
            log.warn("人工调整会面点无解，保留旧点：orderId={} reason={}",
                    orderId, solution.noSolutionReason());
            return Outcome.NO_SOLUTION;
        }
        boolean committed = commit(order, solution.meetLon(), solution.meetLat(),
                solution.meetTMin(), solution.snapshotVersion(), droneId);
        if (!committed) {
            return Outcome.FAILED;
        }
        decisionGate.markCompleted(orderId, System.currentTimeMillis());
        log.info("人工调整会面点已生效：orderId={} 新点=({}, {})",
                orderId, solution.meetLon(), solution.meetLat());
        return Outcome.COMMITTED;
    }

    /** 该船当前在飞、且会面点已落库的订单。 */
    private List<OrderTaskEntity> inFlightOrders(String mmsi) {
        return orderTaskMapper.selectList(new LambdaQueryWrapper<OrderTaskEntity>()
                .eq(OrderTaskEntity::getShipMmsi, mmsi)
                .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES)
                .isNotNull(OrderTaskEntity::getMeetLon)
                .isNotNull(OrderTaskEntity::getMeetLat));
    }

    private boolean recomputeOne(OrderTaskEntity order, PredictionSnapshot snapshot,
            double observedCogDeg, String triggerReason) {
        String orderId = order.getOrderId();
        String droneId = order.getDroneId();
        if (droneId == null || droneId.isBlank()) {
            return false;
        }
        RendezvousRecalcResult result = recomputeWithRetry(order, snapshot, droneId,
                observedCogDeg, triggerReason);
        if (result == null) {
            warnThrottled(orderId, "会面重算失败，保留旧点：orderId={} droneId={}", orderId, droneId);
            return false;
        }
        B_RendezvousSolution solution = result.solution();
        if (!solution.hasSolution()) {
            warnThrottled(orderId, "会面重算无解，保留旧点：orderId={} reason={}",
                    orderId, solution.noSolutionReason());
            return false;
        }
        return commit(order, solution.meetLon(), solution.meetLat(), solution.meetTMin(),
                solution.snapshotVersion(), droneId);
    }

    /**
     * 快照版本可能在「取版本 → 求解」之间被 AIS 刷新推进（M3 会抛版本不一致），
     * 故重试一次并刷新快照；两次都失败返回 {@code null}，由调用方保留旧点并告警。
     */
    private RendezvousRecalcResult recomputeWithRetry(OrderTaskEntity order,
            PredictionSnapshot initial, String droneId, double observedCogDeg,
            String triggerReason) {
        PredictionSnapshot snapshot = initial;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RendezvousRecalcRequest request = buildRequest(order, snapshot, droneId,
                        observedCogDeg, triggerReason);
                RecalcOptions options = buildOptions(order, request.stateSnapshot(),
                        System.currentTimeMillis());
                return rendezvousFacade.recompute(request, options);
            } catch (SnapshotVersionMismatchException e) {
                if (attempt == 0) {
                    Optional<PredictionSnapshot> refreshed;
                    try {
                        refreshed = predictionService.recompute(order.getShipMmsi());
                    } catch (RuntimeException refreshError) {
                        log.debug("会面重算刷新快照失败：orderId={} err={}",
                                order.getOrderId(), refreshError.getMessage());
                        return null;
                    }
                    if (refreshed.isEmpty()) {
                        return null;
                    }
                    snapshot = refreshed.get();
                    continue;
                }
                log.debug("会面重算版本二次不一致：orderId={} err={}",
                        order.getOrderId(), e.getMessage());
            } catch (IllegalArgumentException | IllegalStateException e) {
                warnThrottled(order.getOrderId(),
                        "会面重算数据或配置非法，保留旧点：orderId={} err={}",
                        order.getOrderId(), e.getMessage());
                return null;
            } catch (RuntimeException e) {
                if (attempt == 1) {
                    log.debug("会面重算二次失败：orderId={} err={}",
                            order.getOrderId(), e.getMessage());
                }
            }
        }
        return null;
    }

    private RendezvousRecalcRequest buildRequest(OrderTaskEntity order,
            PredictionSnapshot snapshot, String droneId, double observedCogDeg,
            String triggerReason) {
        List<ShipTrajPoint> points = snapshot.points();
        if (points.isEmpty()) {
            throw new IllegalStateException("预测快照点列为空，无法重算会面点");
        }
        ShipTrajPoint first = points.get(0);
        double cogDeg = Double.isFinite(observedCogDeg) && observedCogDeg >= 0.0
                && observedCogDeg < 360.0
                ? observedCogDeg
                : snapshot.courseDeg();
        if (!Double.isFinite(cogDeg)) {
            throw new IllegalStateException("观测航向与预测主轴方位均不可用，无法重算会面点");
        }
        double sogMs = 0.0;
        if (points.size() > 1) {
            long dtMs = points.get(1).t() - first.t();
            if (dtMs > 0) {
                sogMs = GeoApprox.distanceM(first.lon(), first.lat(),
                        points.get(1).lon(), points.get(1).lat()) / (dtMs / 1000.0);
                sogMs = Math.min(sogMs, ShipState.MAX_SOG_MS);
            }
        }
        RecalcStateSnapshot state = new RecalcStateSnapshot(
                first.lon(), first.lat(), cogDeg, sogMs, first.t(),
                null, null, null, null);
        return new RendezvousRecalcRequest(
                "RECALC-" + order.getOrderId(),
                droneId,
                String.valueOf(snapshot.version()),
                order.getShipMmsi(),
                order.getOrderId(),
                triggerReason,
                System.currentTimeMillis(),
                state);
    }

    private RecalcOptions buildOptions(OrderTaskEntity order, RecalcStateSnapshot state,
            long nowMs) {
        DroneContext drone = droneContextPort.resolve(order.getDroneId());
        double windLon = drone == null ? state.shipLon() : drone.nestLon();
        double windLat = drone == null ? state.shipLat() : drone.nestLat();
        int horizonSec = sysParamLoader.getInt(HORIZON_SEC_KEY, DEFAULT_HORIZON_SEC);
        return new RecalcOptions(
                representativeWind(windLon, windLat, nowMs),
                state.shipStateT() + horizonSec * 1000L,
                sysParamLoader.getInt(WINDOW_MARGIN_SEC_KEY, DEFAULT_WINDOW_MARGIN_SEC),
                sysParamLoader.getDouble(EPSILON_SEC_KEY, DEFAULT_EPSILON_SEC),
                sysParamLoader.getInt(MAX_ITER_KEY, DEFAULT_MAX_ITER));
    }

    private Wind representativeWind(double lon, double lat, long nowMs) {
        WeatherSnapshotPort.LatestWind latest;
        try {
            latest = weatherSnapshotPort.latest(lon, lat, nowMs);
        } catch (RuntimeException e) {
            log.warn("气象快照取数失败，回落系统参数静态风况", e);
            latest = null;
        }
        double fallbackSpeed = sysParamLoader.getDouble(WIND_SPEED_KEY, 0.0);
        double fallbackDirection = sysParamLoader.getDouble(WIND_DIR_DEG_KEY, 0.0);
        if (latest == null) {
            return new Wind(sanitizeSpeed(fallbackSpeed), sanitizeDirection(fallbackDirection), 0.0);
        }
        return new Wind(
                sanitizeSpeed(latest.speedMs(), fallbackSpeed),
                sanitizeDirection(latest.dirDeg(), fallbackDirection),
                0.0);
    }

    private static double sanitizeSpeed(double speed) {
        return Double.isFinite(speed) && speed >= 0.0 ? speed : 0.0;
    }

    private static double sanitizeSpeed(double speed, double fallback) {
        if (Double.isFinite(speed) && speed >= 0.0) {
            return speed;
        }
        return Double.isFinite(fallback) && fallback >= 0.0 ? fallback : 0.0;
    }

    private static double sanitizeDirection(double direction) {
        return Double.isFinite(direction)
                ? ((direction % 360.0) + 360.0) % 360.0
                : 0.0;
    }

    private static double sanitizeDirection(double direction, double fallback) {
        if (Double.isFinite(direction)) {
            return ((direction % 360.0) + 360.0) % 360.0;
        }
        return Double.isFinite(fallback)
                ? ((fallback % 360.0) + 360.0) % 360.0
                : 0.0;
    }

    /**
     * 同事务提交「条件更新坐标 + 发布会面帧」：订单已不在飞则影响行数为 0，不推旧点。
     */
    private boolean commit(OrderTaskEntity order, double meetLon, double meetLat, long meetMs,
            String snapshotVersion, String droneId) {
        Boolean committed = transactionTemplate.execute(status -> {
            int rows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                    .eq(OrderTaskEntity::getOrderId, order.getOrderId())
                    .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES)
                    .set(OrderTaskEntity::getMeetLon, BigDecimal.valueOf(meetLon))
                    .set(OrderTaskEntity::getMeetLat, BigDecimal.valueOf(meetLat)));
            if (rows == 0) {
                log.warn("会面点更新未命中（订单已不在飞）：orderId={}", order.getOrderId());
                return false;
            }
            log.info("会面点已重算并更新：orderId={} 新点=({}, {}) 快照版本={}",
                    order.getOrderId(), meetLon, meetLat, snapshotVersion);
            RendezvousFrame frame = new RendezvousFrameProjector().project(
                    new RendezvousFrameProjector.Input(
                            order.getOrderId(),
                            meetLon,
                            meetLat,
                            true,
                            0,
                            List.of(),
                            toLocalDateTime(meetMs),
                            null,
                            droneId,
                            List.of()));
            eventPublisher.publishEvent(new RendezvousPlanCommittedEvent(frame));
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private static LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), DB_ZONE);
    }

    /** 同一订单在窗口内只告警一次，避免 AIS 高频刷新把日志刷爆。 */
    private void warnThrottled(String key, String format, Object... args) {
        long now = System.currentTimeMillis();
        Long last = lastWarnMs.get(key);
        if (last != null && now - last < WARN_THROTTLE_MS) {
            return;
        }
        if (lastWarnMs.size() >= WARN_THROTTLE_CAPACITY) {
            lastWarnMs.clear();
        }
        lastWarnMs.put(key, now);
        log.warn(format, args);
    }
}
