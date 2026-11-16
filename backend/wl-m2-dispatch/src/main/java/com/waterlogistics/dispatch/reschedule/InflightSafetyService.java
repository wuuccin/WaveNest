package com.waterlogistics.dispatch.reschedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.order.SafetyReview;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.HomecomingInputAssembler;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在飞安全复核的<b>生产接线</b>：告警快照 → 查单 → 判档 → 留痕 → 终止档交功能 10 → 广播。
 *
 * <p>判档的唯一源是 {@link InflightSafetyVerdict#judge}（纯函数、无 IO），本类是<b>编排</b>
 * （查库、写 {@code task_log}、回写订单状态、调功能 10、发事件）。
 * 1-1 曾在 {@code RescheduleService} 挂过一个判档外壳，本批已删除：
 * 生产入口只有本类的 {@link #review}，留两个入口会让下游跳过订单存在性与在飞状态前置。
 *
 * <p><b>为什么不塞进 {@code RescheduleService}</b>：那会让它的构造器从 3 参变 6 参，
 * 连带 7 处单测构造点与装配一起改；且 {@code RescheduleService} 的既有职责是「重调度」，
 * 复核编排属另一条入口。1-1 的三个类型同样按「新增类、不动既有构造器」处理。
 *
 * <h3>三条边界（与 1-1 契约一致，勿在本层放宽）</h3>
 * <ul>
 *   <li><b>只判去程中与末段交接中</b>（《84》§3.3）。返航中的单子已经在中止返航路上，
 *       再判一次档没有可执行动作；其余状态一律空结论。</li>
 *   <li><b>终止档只在功能 10 有可执行落点时回写订单状态</b>（《23》§4 约束 2 的
 *       中止返航例外、§8「订单 去程中 → 返航中」）。回原巢／迫停他巢写 {@code 返航中}；
 *       迫降野外由功能 10 的 {@code settleCargoAndOrder} 置「失败」，本层不覆盖；
 *       功能 10 返回空（缺机巢归属／会面点／电量）时只留审计，不写状态——
 *       否则会出现「订单说返航中、实际没返航」。</li>
 *   <li><b>不读阈值键、不比 8.0／14.0、不看机型抗风上限</b>——检测与分级在 M4
 *       （{@code WeatherViolationDetector}），本层只消费告警侧给出的级别（《84》§3.3）。</li>
 * </ul>
 *
 * <h3>位置取哪里的（本批已知简化）</h3>
 * <p>气象告警不带机位：{@code AlertEventMessage} 的 {@code lon/lat} 在 2-3 组装
 * {@link InflightSafetyRequest} 时未进契约（1-1 九字段里没有它）。本批以<b>订单会面点</b>
 * 作当前位置与返程起点——它是在飞无人机「该到的点」，用它算返程距离偏大，属保守侧；
 * 订单无会面点时<b>不执行功能 10</b>，只留审计，不臆造坐标。风况快照传 {@code null}，
 * 按《84》§7.3「无风况快照则按固定裕度系数 k 判」降级，不伪造风值。
 */
@Service
public class InflightSafetyService {

    private static final Logger log = LoggerFactory.getLogger(InflightSafetyService.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 复核范围：去程中与末段交接中（《84》§3.3） */
    private static final Set<OrderStatus> REVIEWABLE = EnumSet.of(
            OrderStatus.OUTBOUND, OrderStatus.HANDOVER);

    /** 货物仍在机上：订单机号已绑定且尚未交接（《84》§7.3「货机同行，未交接则随货返航」） */
    private static final Set<OrderStatus> CARGO_ONBOARD = EnumSet.of(
            OrderStatus.DISPATCHED, OrderStatus.OUTBOUND, OrderStatus.HANDOVER);

    /** 气象终止档的中止原因，落功能 10 输入（《84》§7.3「中止原因（细分枚举）」） */
    private static final String ABORT_REASON_WEATHER = "气象超限";

    /** 中止返航迁移行的 op_type（《23》§8「订单 去程中 → 返航中」） */
    private static final String OP_ABORT_RETURN = "在飞安全复核-中止返航";

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DroneMapper droneMapper;
    private final RescheduleService rescheduleService;
    private final HomecomingInputAssembler inputAssembler;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final TaskQueueService taskQueueService;

    /**
     * @param inputAssembler 只借它的 {@link HomecomingInputAssembler#params()}：
     *                       功能 10 的阈值换算（{@code e10ReserveRatio}×100 等）已在那边落过一次，
     *                       本类不复制同一套读键与换算，避免两处漂移
     */
    @Autowired
    public InflightSafetyService(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            DroneMapper droneMapper,
            RescheduleService rescheduleService,
            HomecomingInputAssembler inputAssembler,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate,
            TaskQueueService taskQueueService) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.droneMapper = droneMapper;
        this.rescheduleService = rescheduleService;
        this.inputAssembler = inputAssembler;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.taskQueueService = taskQueueService;
    }

    /**
     * 复核一条气象告警对一个在飞订单的影响。
     *
     * <p><b>事务口径</b>：留痕与终止档调功能 10 在同一事务内（功能 10 抛错则整体回滚，
     * 不留下「审计说终止、实际没返航」的半条链）；<b>广播在事务之外</b>，避免订阅方
     * 在事务未提交时读到旧状态。
     *
     * <p><b>幂等</b>：本方法不做 eventId 去重——同一告警重投会再走一遍。去重在调用方
     * （{@code AlertDrivenDispatcher.claimEvent} 已按 eventId 占位），本层不重复造一套。
     *
     * @param request 告警快照；为 {@code null} 时返回空结论
     * @return 三值结论；「没判」（订单不存在／状态不符／入参不合法）时为空，
     *         调用方不得据此放行（空 ≠ 判过且安全）
     */
    public Optional<InflightSafetyVerdict> review(InflightSafetyRequest request) {
        Optional<ReviewOutcome> outcome = runInTransaction(() -> reviewInTransaction(request));
        // 三档都带复核结论走 order_state（继续档也要留痕给展示层）；
        // 原广播独立保留：调整／终止档仍发 InflightSafetyDecisionEvent，继续档不发。
        outcome.ifPresent(this::publishSafetyReview);
        outcome.filter(o -> o.verdict().riskMarked()).ifPresent(this::publish);
        return outcome.map(ReviewOutcome::verdict);
    }

    // ───────────────────────── 事务内 ─────────────────────────

    private Optional<ReviewOutcome> reviewInTransaction(InflightSafetyRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        OrderTaskEntity order = resolveOrder(request);
        if (order == null) {
            return Optional.empty();
        }
        OrderStatus status = statusOf(order);
        if (status == null || !REVIEWABLE.contains(status)) {
            log.info("在飞安全复核跳过：订单不在去程中与末段交接中 orderId={} status={} eventId={}",
                    order.getOrderId(), order.getStatus(), request.eventId());
            return Optional.empty();
        }

        Optional<InflightSafetyVerdict> judged = InflightSafetyVerdict.judge(request);
        if (judged.isEmpty()) {
            // P3／P4 不合法：不兜成「继续」，只记警告交调用方处置
            log.warn("在飞安全复核未判：风速或告警级别不可判 eventId={} orderId={} wind={} level={}",
                    request.eventId(), order.getOrderId(), request.windSpeedMs(), request.alertLevel());
            return Optional.empty();
        }
        InflightSafetyVerdict verdict = judged.get();

        TaskLogEntity audit = writeAudit(order, verdict);

        HomecomingDecision decision = null;
        if (verdict.verdict() == InflightSafetyVerdict.Verdict.TERMINATE) {
            decision = returnHome(order, request);
            // 迫降野外时功能 10 已把订单置「失败」并写了自己的迁移行，本层不覆盖；
            // 返回空（缺机巢归属／会面点／电量）时只留审计，不能凭审计说已返航。
            if (decision != null && decision.tier() != HomecomingTier.EMERGENCY_LAND_FIELD) {
                transitionToReturning(order, status, reviewOf(verdict, audit));
            }
        }
        log.info("在飞安全复核完成：orderId={} droneId={} 结论={} 落点={} 订单态={} eventId={}",
                order.getOrderId(), request.droneId(), verdict.verdict().getValue(),
                decision == null ? "不适用" : decision.tier().getValue(),
                order.getStatus(), request.eventId());
        return Optional.of(new ReviewOutcome(verdict, order, decision, audit));
    }

    /**
     * 落 {@code task_log}：<b>审计不是状态迁移</b>，from 与 to 同取当前订单状态。
     *
     * <p>继续档与调整档只有这一条；终止档在功能 10 有可执行落点时会再写一条
     * {@code from → 返航中} 的迁移行，两条的 op_type 与语义不同，勿合并。
     *
     * <p>失败不吞：审计是「终止」这一动作的唯一凭证，写不进去就不该继续往功能 10 走
     * （与既有 {@code writeAudit} 的 fail-soft 口径不同——那里是复核结论的旁证，
     * 这里是可执行动作的前置记录）。
     */
    private TaskLogEntity writeAudit(OrderTaskEntity order, InflightSafetyVerdict verdict) {
        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(order.getOrderId());
        entry.setOpType(verdict.verdict().getOpType());
        entry.setFromStatus(order.getStatus());
        entry.setToStatus(order.getStatus());
        entry.setTs(LocalDateTime.ofInstant(Instant.now(), DB_ZONE));
        taskLogMapper.insert(entry);
        return entry;
    }

    /**
     * 中止返航：同一事务内条件更新订单状态、写迁移行、发状态事件。
     *
     * <p><b>顺序不可换</b>：迁移行的 {@code logId} 必须大于 {@link #writeAudit} 的审计行——
     * {@code FlightPhaseAdvancer.lastCyclePhase} 取「派发日志之后、toStatus 属飞行四态、
     * logId 最大」那一条，顺序反了会读回迁移前的阶段。
     *
     * <p>{@code OrderStateChangedEvent} 在事务内发布：advancer 的
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 靠它给周期缓存标脏，
     * 不发则后续每次 emit 的条件更新命中 0 行并刷 error 日志。
     *
     * <p>ETA 与 {@code TaskQueueService.transition} 同口径传 {@code null}：
     * 本层不重算返航段预计送达，也不改库里的 {@code eta_ts}。
     */
    private void transitionToReturning(OrderTaskEntity order, OrderStatus from,
            SafetyReview review) {
        int updated = orderTaskMapper.update(null,
                Wrappers.<OrderTaskEntity>lambdaUpdate()
                        .eq(OrderTaskEntity::getOrderId, order.getOrderId())
                        .eq(OrderTaskEntity::getStatus, from.getValue())
                        .set(OrderTaskEntity::getStatus, OrderStatus.RETURNING.getValue()));
        if (updated != 1) {
            throw new IllegalStateException("订单 " + order.getOrderId()
                    + " 中止返航回写冲突：从 " + from.getValue() + " 流转到 "
                    + OrderStatus.RETURNING.getValue() + " 失败");
        }
        order.setStatus(OrderStatus.RETURNING.getValue());

        long eventTs = System.currentTimeMillis();
        TaskLogEntity migration = new TaskLogEntity();
        migration.setOrderId(order.getOrderId());
        migration.setOpType(OP_ABORT_RETURN);
        migration.setFromStatus(from.getValue());
        migration.setToStatus(OrderStatus.RETURNING.getValue());
        migration.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(eventTs), DB_ZONE));
        taskLogMapper.insert(migration);

        // 复用 TaskQueueService.stateVersion：它先取 max(2, logId+1)，再记住已发布最大版本，
        // 后续 logId==null 的帧在其上自增；不能自行写 Math.max，否则版本记忆位缺失会让帧被前端拒收。
        long version = taskQueueService.stateVersion(order.getOrderId(), migration.getLogId());
        // 配送品类、货巢与复核三值同为建单／复核已定的字段：本帧不带会被前端字段级合并抹掉。
        // 终止档迁移帧与复核帧带同一份 SafetyReview：迁移帧 AFTER_COMMIT 先到且版本更大，
        // 复核帧随后到会被严格递增判据丢弃——迁移帧必须自身携带，终止档三值才带得出来。
        OrderView view = new OrderView(order.getOrderId(), OrderStatus.RETURNING, eventTs,
                version, null, null, null, null, etaTsOf(order), order.getCargoNestId(),
                order.getGoodsType(), review);
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));
    }

    /**
     * 终止档：构造功能 10 输入 → 放弃本次会面、原机返航（三级落点判定）。
     *
     * <p><b>不复用 {@link HomecomingInputAssembler#build}</b>：它只对低电量告警取电量，
     * 气象告警调它会因电量不可得整体跳过（MEDIUM.7 针对失联，不适用于气象——气象告警时
     * 遥测正常，{@code drone.battery} 就是最新实测）。
     *
     * <p>五项必填缺任一项即不执行，只返回 {@code null} 并记警告：机巢归属、会面点、
     * 电量、订单状态可解析。缺失不臆造——算不出返程就交给人工，不假造一个落点。
     */
    private HomecomingDecision returnHome(OrderTaskEntity order, InflightSafetyRequest request) {
        DroneEntity drone = droneMapper.selectById(request.droneId());
        if (drone == null || isBlank(drone.getHomeNestId())) {
            log.warn("终止档未执行返航复核：无人机或归属机巢缺失 droneId={} orderId={}",
                    request.droneId(), order.getOrderId());
            return null;
        }
        GeoPoint position = meetPointOf(order);
        if (position == null) {
            log.warn("终止档未执行返航复核：订单无会面点坐标，不臆造机位 orderId={}",
                    order.getOrderId());
            return null;
        }
        Double battery = drone.getBattery() == null ? null : drone.getBattery().doubleValue();
        if (battery == null || !Double.isFinite(battery) || battery < 0 || battery > 100) {
            log.warn("终止档未执行返航复核：电量不可得 droneId={} 实测={} orderId={}",
                    request.droneId(), drone.getBattery(), order.getOrderId());
            return null;
        }
        OrderStatus status = statusOf(order);
        if (status == null) {
            return null;
        }

        HomecomingParams params = inputAssembler.params();
        HomecomingInput input = new HomecomingInput(
                request.eventId(),
                request.trigger().getValue(),
                order.getOrderId(),
                order.getOrderId(),
                request.droneId(),
                drone.getHomeNestId(),
                position,
                battery,
                params.batterySafetyMarginPct(),
                CARGO_ONBOARD.contains(status),
                null,
                null,
                ABORT_REASON_WEATHER,
                request.observedAtMs(),
                Boolean.TRUE);
        // 不 catch：功能 10 抛错即回滚本事务，审计一并撤掉，避免「记了终止但没返航」
        return rescheduleService.abandonMeetAndReturnHome(input, params, List.of());
    }

    /**
     * 订单解析：请求带单号即以该单为准；未带则按机号反查<b>当前在飞</b>的订单
     * （只认去程中与末段交接中，返航中的单子不该被气象告警重新拉回来判档）。
     */
    private OrderTaskEntity resolveOrder(InflightSafetyRequest request) {
        if (!isBlank(request.orderId())) {
            OrderTaskEntity order = orderTaskMapper.selectById(request.orderId());
            if (order == null) {
                log.warn("在飞安全复核跳过：订单不存在 orderId={} eventId={}",
                        request.orderId(), request.eventId());
            }
            return order;
        }
        OrderTaskEntity order = orderTaskMapper.selectOne(new LambdaQueryWrapper<OrderTaskEntity>()
                .eq(OrderTaskEntity::getDroneId, request.droneId())
                .in(OrderTaskEntity::getStatus,
                        REVIEWABLE.stream().map(OrderStatus::getValue).toList())
                .orderByDesc(OrderTaskEntity::getCreateTime)
                .last("LIMIT 1"));
        if (order == null) {
            log.warn("在飞安全复核跳过：告警未带单号且按机号反查未命中在飞订单 droneId={} eventId={}",
                    request.droneId(), request.eventId());
        }
        return order;
    }

    /** 订单会面点；坐标缺失或越界时为空，不臆造位置。 */
    private static GeoPoint meetPointOf(OrderTaskEntity order) {
        if (order.getMeetLon() == null || order.getMeetLat() == null) {
            return null;
        }
        double lon = order.getMeetLon().doubleValue();
        double lat = order.getMeetLat().doubleValue();
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            return null;
        }
        return new GeoPoint(lon, lat);
    }

    private static OrderStatus statusOf(OrderTaskEntity order) {
        try {
            return OrderStatus.fromValue(order.getStatus());
        } catch (RuntimeException e) {
            log.warn("在飞安全复核跳过：订单状态不可解析 orderId={} status={}",
                    order.getOrderId(), order.getStatus());
            return null;
        }
    }

    // ───────────────────────── 事务外 ─────────────────────────

    /**
     * 三档共用的复核三值：结论与操作词原样取自裁决（中文词，不翻译、不映射），
     * 时刻取审计行 {@code ts}。审计行由 {@link #writeAudit} 用 {@code Instant.now()}
     * 配 {@link #DB_ZONE} 写入，这里必须用同一时区反解，否则毫秒数整体偏 8 小时，
     * 前端按 {@code eventTs} 排序会把复核帧排到队首或队尾、与其它帧对不上。
     */
    private static SafetyReview reviewOf(InflightSafetyVerdict verdict, TaskLogEntity audit) {
        long eventTs = audit.getTs() == null ? System.currentTimeMillis()
                : audit.getTs().atZone(DB_ZONE).toInstant().toEpochMilli();
        return new SafetyReview(verdict.verdict().getValue(), verdict.verdict().getOpType(), eventTs);
    }

    /**
     * 订单实体的预计送达时刻转 Unix 毫秒，口径与 {@code FlightPhaseAdvancer} 广播帧一致：
     * 写库用 {@link #DB_ZONE} 转 {@code LocalDateTime}，这里用同一时区反解。复核本身不改送达时刻，
     * 但帧里若留 null，前端按整对象替换会把已展示的「预计送达」抹空，故原样带出。
     */
    private static Long etaTsOf(OrderTaskEntity order) {
        return order.getEtaTs() == null ? null
                : order.getEtaTs().atZone(DB_ZONE).toInstant().toEpochMilli();
    }

    /**
     * 三档都随 {@code order_state} 带出复核结论：继续档不改状态也发一帧（留痕给展示层），
     * 调整档同理；终止档的迁移帧在事务内先发，本帧后发、版本较小会被前端严格递增判据
     * 丢弃，但迁移帧已带同一份复核三值，结论不会丢。
     *
     * <p>版本复用 {@link TaskQueueService#stateVersion}（含 fallback 记忆位），日志主键
     * 取审计行——版本与时刻同源，都来自本次复核落下的那条 {@code task_log}。
     */
    private void publishSafetyReview(ReviewOutcome outcome) {
        InflightSafetyVerdict verdict = outcome.verdict();
        SafetyReview review = reviewOf(verdict, outcome.audit());
        long eventTs = review.eventTs();
        long version = taskQueueService.stateVersion(
                outcome.order().getOrderId(), outcome.audit().getLogId());
        // etaTs 与货巢、品类同为复核时订单上已定的事实：帧里留 null 会把前端已展示的「预计送达」抹空。
        OrderView view = new OrderView(outcome.order().getOrderId(),
                statusOf(outcome.order()), eventTs, version, null, null, null, null,
                etaTsOf(outcome.order()), outcome.order().getCargoNestId(),
                outcome.order().getGoodsType(), review);
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));
    }

    /**
     * 调整与终止两档广播；继续档不发（见 {@link InflightSafetyDecisionEvent}）。
     *
     * <p>广播失败只告警不外抛：复核结果与审计已在事务里落定，推送失败不该把一次
     * 已生效的复核变成失败重投。
     */
    private void publish(ReviewOutcome outcome) {
        InflightSafetyVerdict verdict = outcome.verdict();
        HomecomingDecision decision = outcome.decision();
        try {
            eventPublisher.publishEvent(new InflightSafetyDecisionEvent(
                    verdict.eventId(),
                    outcome.order().getOrderId(),
                    verdict.droneId(),
                    verdict.verdict(),
                    verdict.windSpeedMs(),
                    verdict.alertLevel(),
                    verdict.reason(),
                    decision == null ? null : decision.tier(),
                    decision == null ? null : decision.targetId()));
        } catch (RuntimeException e) {
            log.warn("在飞安全复核广播失败（审计与动作已提交）：orderId={} 结论={} err={}",
                    outcome.order().getOrderId(), verdict.verdict().getValue(), e.getMessage());
        }
    }

    /** 无事务模板时（纯单测）退化为直接执行，与 {@code FlightPhaseAdvancer} 同一写法。 */
    private Optional<ReviewOutcome> runInTransaction(Supplier<Optional<ReviewOutcome>> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        Optional<ReviewOutcome> result = transactionTemplate.execute(status -> action.get());
        return result == null ? Optional.empty() : result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 复核结果四元组：结论 ＋ 权威订单行 ＋ 落点结论（非终止档为空）＋ 本次审计行。 */
    private record ReviewOutcome(InflightSafetyVerdict verdict, OrderTaskEntity order,
            HomecomingDecision decision, TaskLogEntity audit) {
    }
}
