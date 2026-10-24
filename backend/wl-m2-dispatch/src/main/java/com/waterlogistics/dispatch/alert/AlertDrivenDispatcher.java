package com.waterlogistics.dispatch.alert;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.dispatch.homecoming.HomecomingService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 告警驱动重调度消费者（F1 · 2026-10-23）——M2 消费 {@code alert.event} 的<b>入端</b>。
 *
 * <p>订阅 M4 发的告警事件（契约 {@link AlertEventMessage}，F0a），按类型分派
 * （《10-23 清单》F1 分派表）：
 * <ol>
 *   <li><b>偏航</b> → 调重调度 {@link RescheduleService#reschedule}（重入队 ＋ 派发编排）；
 *       清单中的「意图变更」样本走 {@code rendezvous.deviation} 回流、<b>不走告警通道</b>
 *       （{@code TopicName} javadoc 明文），告警面可观察的只有偏航型，由本分支覆盖；</li>
 *   <li><b>低电量／失联</b> → 组装返航输入（{@link HomecomingInputAssembler}），调
 *       {@link HomecomingService#reviewAndDecide} 三级落点判定；决策产出后
 *       <b>落审计（task_log）＋广播（{@link HomecomingDecisionEvent}）即止</b>——
 *       三个下游动作（返航航迹／归巢时隙占位／返航指令）按清单保持不接实；</li>
 *   <li><b>气象类</b> → 本类不动作：熔断置位/解除由 B2 的 {@code WeatherGateTrigger} 管，
 *       解除后的积压唤醒由 {@link WeatherClearWaker} 管（本包内，边沿轮询）；</li>
 *   <li><b>链路异常</b> → 无订单侧动作，记日志即可。</li>
 * </ol>
 *
 * <h3>消费纪律（《10-23 清单》F1 边界 3）</h3>
 * <ul>
 *   <li><b>消费失败吞掉并记日志</b>：一条坏消息（未知类型／字段缺失／下游抛错）
 *       只丢弃该条，<b>不得中断整个队列</b>；</li>
 *   <li><b>只新增文件</b>：本包只调用既有服务的现有方法，不改重调度／返航复核的
 *       签名与构造器；</li>
 *   <li><b>返航复核按 eventId 去重</b>：同一告警一个 eventId 贯穿始终（G-01），
 *       级别变化会复用 id 再发——复核一条告警只做一次（内存去重，重启后最多重复一次，
 *       审计口径可接受）。</li>
 * </ul>
 *
 * <p><b>前置依赖</b>：消息类型解析依赖统一 Jackson 转换器（组装层唯一，新 record 自动映射）；
 * F0b 合入前 M4 发送端仍发检测层内部类型，{@code __TypeId__} 在本侧不可解析——
 * F0b 切换为 {@link AlertEventMessage} 后本监听器即通，无需改动。
 *
 * <p>拓扑（与 {@code WeatherIngestListener} 同套写法，队列与绑定属消费侧责任）：
 * <pre>
 *   exchange: wavenest.data (topic) --routing key: alert.event--> queue: wavenest.m2.alert-event
 * </pre>
 */
@Component
public class AlertDrivenDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDrivenDispatcher.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final RescheduleService rescheduleService;
    private final HomecomingService homecomingService;
    private final HomecomingInputAssembler assembler;
    private final OrderTaskMapper orderTaskMapper;
    private final DroneMapper droneMapper;
    private final TaskLogMapper taskLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 已复核过的告警事件 id（返航复核按 eventId 去重，见类注释） */
    private final Set<String> reviewedEventIds = ConcurrentHashMap.newKeySet();

    public AlertDrivenDispatcher(RescheduleService rescheduleService,
            HomecomingService homecomingService,
            HomecomingInputAssembler assembler,
            OrderTaskMapper orderTaskMapper,
            DroneMapper droneMapper,
            TaskLogMapper taskLogMapper,
            ApplicationEventPublisher eventPublisher) {
        this.rescheduleService = rescheduleService;
        this.homecomingService = homecomingService;
        this.assembler = assembler;
        this.orderTaskMapper = orderTaskMapper;
        this.droneMapper = droneMapper;
        this.taskLogMapper = taskLogMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 消费一条告警事件。任何异常只丢弃本条并记警告，不向外抛——
     * 单条坏消息不得中断整个队列。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-alert-event-queue:wavenest.m2.alert-event}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.ALERT_EVENT))
    public void onAlertEvent(AlertEventMessage message) {
        if (message == null || message.eventId() == null) {
            log.warn("丢弃非法告警消息：null 或缺 eventId");
            return;
        }
        try {
            route(message);
        } catch (Exception e) {
            log.warn("告警消息处理失败已丢弃（不中断队列）：eventId={} type={} orderId={} 原因={}",
                    message.eventId(), message.alertType(), message.orderId(), e.getMessage());
        }
    }

    /** 按告警类型分派（公开供测试直调，绕过 MQ）。 */
    public void route(AlertEventMessage message) {
        AlertType type = AlertType.fromValue(message.alertType());
        switch (type) {
            case DEVIATION -> handleDeviation(message);
            case LOW_BATTERY, LOST_LINK -> handleHomecoming(message);
            case WEATHER_EXCEED, LINK_ERROR -> log.info(
                    "告警 {} 无调度侧动作：eventId={} orderId={}（气象由闸门链路管，链路异常无订单动作）",
                    type.getValue(), message.eventId(), message.orderId());
        }
    }

    /** 分派表第 1 行：偏航 → 重调度（重入队 ＋ 派发编排）。 */
    private void handleDeviation(AlertEventMessage message) {
        if (isBlank(message.orderId())) {
            log.warn("偏航告警缺订单号，无法重调度：eventId={} droneId={}",
                    message.eventId(), message.droneId());
            return;
        }
        String droneId = rescheduleService.reschedule(message.orderId());
        log.info("偏航告警触发重调度完成：orderId={} 新机={} eventId={}",
                message.orderId(), droneId, message.eventId());
    }

    /** 分派表第 2 行：低电量／失联 → 返航复核（决策落审计 ＋ 广播，下游不接实）。 */
    private void handleHomecoming(AlertEventMessage message) {
        if (isBlank(message.orderId())) {
            log.warn("告警缺订单号（失联早于订单绑定属正常），无法返航复核：eventId={} type={}",
                    message.eventId(), message.alertType());
            return;
        }
        if (!reviewedEventIds.add(message.eventId())) {
            log.info("同一告警事件已复核过，去重跳过：eventId={}", message.eventId());
            return;
        }
        OrderTaskEntity order = orderTaskMapper.selectById(message.orderId());
        if (order == null) {
            log.warn("返航复核跳过：订单不存在 orderId={} eventId={}",
                    message.orderId(), message.eventId());
            return;
        }
        HomecomingInput input = assembler.build(message, order,
                message.droneId() == null ? null : droneMapper.selectById(message.droneId()));
        if (input == null) {
            return;
        }
        HomecomingParams params = assembler.params();
        // 候选他巢台账传空：第二级需 B 组返程能耗精算，未接——降级到一／三级，不伪造能耗
        HomecomingDecision decision = homecomingService.reviewAndDecide(input, params, List.of());
        writeAudit(order, message, decision);
        eventPublisher.publishEvent(new HomecomingDecisionEvent(
                message.eventId(), message.alertType(), order.getOrderId(), message.droneId(),
                decision.tier(), decision.targetId(), decision.needSlotRequest(), decision.reason()));
        log.info("告警驱动返航复核完成：orderId={} tier={} eventId={}",
                order.getOrderId(), decision.tier().getValue(), message.eventId());
    }

    /**
     * 决策审计落 {@code task_log}：审计不是状态迁移，from/to 记当前订单状态、
     * 操作类型带告警类型；落库失败 fail-soft（决策日志与广播已先行，不因审计失败重抛）。
     */
    private void writeAudit(OrderTaskEntity order, AlertEventMessage message,
            HomecomingDecision decision) {
        try {
            TaskLogEntity audit = new TaskLogEntity();
            audit.setOrderId(order.getOrderId());
            audit.setOpType("返航复核-" + message.alertType());
            audit.setFromStatus(order.getStatus());
            audit.setToStatus(order.getStatus());
            audit.setTs(LocalDateTime.ofInstant(Instant.now(), DB_ZONE));
            taskLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("返航复核审计落库失败（fail-soft）：orderId={} tier={} err={}",
                    order.getOrderId(), decision.tier().getValue(), e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
