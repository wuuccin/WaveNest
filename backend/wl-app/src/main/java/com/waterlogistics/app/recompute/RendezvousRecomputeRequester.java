package com.waterlogistics.app.recompute;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.frame.RendezvousFrameProjector;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.dispatch.prediction.RecomputeRequest;
import com.waterlogistics.dispatch.prediction.RecomputeRequester;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
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
 * 六a 纠偏接会面点（阶段一 15）——把「航向跳变 ≥20°」的重算信号接到 M3 会面求解。
 *
 * <p><b>链路</b>：{@link PredictionService#recompute(String)} 刷新快照
 * → 按 {@code ship_mmsi} 查在飞订单 → {@link RendezvousCoordinator#solveFor}
 * 求新会面点 → 条件更新 {@code order_task.meet_lon/meet_lat}
 * → 发布 {@link RendezvousPlanCommittedEvent}（由 {@code RealtimeEventBridge} 转 {@code rendezvous} 帧）。
 *
 * <p><b>comboId 口径</b>：重算路径回溯不到派发时的候选组合，{@link RendezvousSolveRequest} 也明确
 * 允许为 {@code null}；但 M3 多个 Command 对非空有校验，故用合成标识
 * {@code RECALC-{orderId}}——求解器只把它用于 {@code solutionId} 拼接与结果回填，不参与数学计算。
 *
 * <p><b>只更新坐标、不动 {@code meet_time}</b>：会面点微调不应触发
 * {@code FlightPhaseAdvancer} 重建派发周期时间轴；末段切入已由任务 17 的位置判定兜底。
 *
 * <p><b>失败语义</b>：求解/快照缺失一律保留旧点，不向上抛（本方法在预测刷新热路径上，
 * 下游不可用不得带停预测链）；失败告警按订单节流，避免每个 AIS 刷新刷屏。
 *
 * <p><b>装配</b>：{@code @Primary} 优先于默认实现 {@code LoggingRecomputeRequester}，
 * 见 {@link RecomputeRequester} 的装配规则。
 */
@Component
@Primary
public class RendezvousRecomputeRequester implements RecomputeRequester {

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

    private final PredictionService predictionService;
    private final OrderTaskMapper orderTaskMapper;
    private final RendezvousCoordinator rendezvousCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /** orderId → 最近一次告警时刻（毫秒），仅用于节流。 */
    private final Map<String, Long> lastWarnMs = new ConcurrentHashMap<>();

    /**
     * @param predictionService 预测服务（{@code @Lazy} 打断“预测 ↔ 重算”构造环）
     * @param orderTaskMapper   订单表
     * @param rendezvousCoordinator 会面求解入口（M2 内唯一入口；{@code @Lazy} 断开
     *        15 → Coordinator → RendezvousFacade → TrajectorySnapshotAdapter → PredictionService → 15 的环，
     *        本字段只在运行期 requestRecompute 使用，延迟无副作用）
     * @param eventPublisher    发布已提交会面方案
     * @param transactionTemplate 同事务提交「坐标更新 + 事件」，避免推送早于落库
     */
    public RendezvousRecomputeRequester(@Lazy PredictionService predictionService,
            OrderTaskMapper orderTaskMapper,
            @Lazy RendezvousCoordinator rendezvousCoordinator,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this.predictionService = predictionService;
        this.orderTaskMapper = orderTaskMapper;
        this.rendezvousCoordinator = rendezvousCoordinator;
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
            for (OrderTaskEntity order : orders) {
                recomputeOne(order);
            }
        } catch (RuntimeException e) {
            // 预测刷新热路径：失败只告警，不影响预测链本身
            log.warn("会面重算失败，保留旧会面点：mmsi={} err={}", request.mmsi(), e.getMessage());
        }
    }

    /** 该船当前在飞、且会面点已落库的订单。 */
    private List<OrderTaskEntity> inFlightOrders(String mmsi) {
        return orderTaskMapper.selectList(new LambdaQueryWrapper<OrderTaskEntity>()
                .eq(OrderTaskEntity::getShipMmsi, mmsi)
                .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES)
                .isNotNull(OrderTaskEntity::getMeetLon)
                .isNotNull(OrderTaskEntity::getMeetLat));
    }

    private void recomputeOne(OrderTaskEntity order) {
        String orderId = order.getOrderId();
        String droneId = order.getDroneId();
        if (droneId == null) {
            return;
        }
        MeetPlan plan = solveWithRetry(order, "RECALC-" + orderId, droneId);
        if (plan == null) {
            warnThrottled(orderId, "会面求解失败，保留旧点：orderId={} droneId={}", orderId, droneId);
            return;
        }
        commit(order, plan, droneId);
    }

    /**
     * 快照版本可能在「取版本 → 求解」之间被 AIS 刷新推进（M3 会抛版本不一致），
     * 故重试一次；两次都失败返回 {@code null}，由调用方保留旧点并告警。
     */
    private MeetPlan solveWithRetry(OrderTaskEntity order, String comboId, String droneId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return rendezvousCoordinator.solveFor(order, comboId, droneId);
            } catch (RuntimeException e) {
                if (attempt == 1) {
                    log.debug("会面求解二次失败：orderId={} err={}", order.getOrderId(), e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 同事务提交「条件更新坐标 + 发布会面帧」：订单已不在飞则影响行数为 0，不推旧点。
     */
    private void commit(OrderTaskEntity order, MeetPlan plan, String droneId) {
        transactionTemplate.execute(status -> {
            int rows = orderTaskMapper.update(null, new LambdaUpdateWrapper<OrderTaskEntity>()
                    .eq(OrderTaskEntity::getOrderId, order.getOrderId())
                    .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES)
                    .set(OrderTaskEntity::getMeetLon, BigDecimal.valueOf(plan.meetLon()))
                    .set(OrderTaskEntity::getMeetLat, BigDecimal.valueOf(plan.meetLat())));
            if (rows == 0) {
                log.warn("会面点更新未命中（订单已不在飞）：orderId={}", order.getOrderId());
                return null;
            }
            log.info("会面点已重算并更新：orderId={} 新点=({}, {}) 快照版本={}",
                    order.getOrderId(), plan.meetLon(), plan.meetLat(), plan.snapshotVersion());
            RendezvousFrame frame = new RendezvousFrameProjector().project(
                    new RendezvousFrameProjector.Input(
                            order.getOrderId(),
                            plan.meetLon(),
                            plan.meetLat(),
                            true,
                            0,
                            List.of(),
                            plan.meetTime(),
                            null,
                            droneId,
                            List.of()));
            eventPublisher.publishEvent(new RendezvousPlanCommittedEvent(frame));
            return null;
        });
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
