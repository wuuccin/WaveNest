package com.waterlogistics.dispatch.reschedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.HomecomingInputAssembler;
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
 * <p>与 {@link RescheduleService#evaluateInflightSafety} 的分工：那一个是<b>纯判档</b>（无 IO，
 * 单测钉三条判档与六类失败），本类是<b>编排</b>（查库、写 {@code task_log}、调功能 10、发事件）。
 * 判据不在这里重写——一律调 {@link InflightSafetyVerdict#judge}，改判档只改一处。
 *
 * <p><b>为什么不塞进 {@code RescheduleService}</b>：那会让它的构造器从 3 参变 6 参，
 * 连带 7 处单测构造点与装配一起改；且 {@code RescheduleService} 的既有职责是「重调度」，
 * 复核编排属另一条入口。1-1 的三个类型同样按「新增类、不动既有构造器」处理。
 *
 * <h3>三条边界（与 1-1 契约一致，勿在本层放宽）</h3>
 * <ul>
 *   <li><b>只判去程中与末段交接中</b>（《84》§3.3）。返航中的单子已经在中止返航路上，
 *       再判一次档没有可执行动作；其余状态一律空结论。</li>
 *   <li><b>终止档不写订单状态</b>（《23》§3 约束 2：风速突变不新增任务状态，状态停在原阶段）。
 *       落点由功能 10 判定后写无人机状态，订单由 {@code FlightPhaseAdvancer} 继续推进。</li>
 *   <li><b>不读阈值键、不比 8.0／14.0、不看机型抗风上限</b>——检测与分级在 M4，
 *       本层只消费告警侧给出的级别（《84》§3.3）。</li>
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

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DroneMapper droneMapper;
    private final RescheduleService rescheduleService;
    private final HomecomingInputAssembler inputAssembler;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

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
            TransactionTemplate transactionTemplate) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.droneMapper = droneMapper;
        this.rescheduleService = rescheduleService;
        this.inputAssembler = inputAssembler;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
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

        writeAudit(order, verdict);

        HomecomingDecision decision = verdict.verdict() == InflightSafetyVerdict.Verdict.TERMINATE
                ? returnHome(order, request)
                : null;
        log.info("在飞安全复核完成：orderId={} droneId={} 结论={} 落点={} eventId={}",
                order.getOrderId(), request.droneId(), verdict.verdict().getValue(),
                decision == null ? "不适用" : decision.tier().getValue(), request.eventId());
        return Optional.of(new ReviewOutcome(verdict, order, decision));
    }

    /**
     * 落 {@code task_log}：<b>审计不是状态迁移</b>，from 与 to 同取当前订单状态。
     *
     * <p>失败不吞：审计是「终止」这一动作的唯一凭证，写不进去就不该继续往功能 10 走
     * （与既有 {@code writeAudit} 的 fail-soft 口径不同——那里是复核结论的旁证，
     * 这里是可执行动作的前置记录）。
     */
    private void writeAudit(OrderTaskEntity order, InflightSafetyVerdict verdict) {
        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(order.getOrderId());
        entry.setOpType(verdict.verdict().getOpType());
        entry.setFromStatus(order.getStatus());
        entry.setToStatus(order.getStatus());
        entry.setTs(LocalDateTime.ofInstant(Instant.now(), DB_ZONE));
        taskLogMapper.insert(entry);
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

    /** 复核结果三元组：结论 ＋ 权威订单行 ＋ 终止档的落点结论（非终止档为空）。 */
    private record ReviewOutcome(InflightSafetyVerdict verdict, OrderTaskEntity order,
            HomecomingDecision decision) {
    }
}
