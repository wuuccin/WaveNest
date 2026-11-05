package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import com.waterlogistics.dispatch.taskstate.FlightPhaseChangedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单接入与接单判定（M2 · 卢肄文 · 模块二功能 1）。
 *
 * <p><b>阶段一 26 接线后的完整口径</b>：下单先过基本校验（形式合法性），再经
 * {@link AdmissionInputAssembler} 装配数据源、{@link AdmissionPipeline#admit} 跑完整判定链，
 * 按结论四分流：
 * <ul>
 *   <li>{@code ACCEPT_TRIGGER_SCHEDULE} → 建单「待受理」＋ 发布 {@link OrderSubmittedEvent}
 *       （{@code waitReason=null}），由 DispatchTrigger 落库并驱动调度主链；</li>
 *   <li>{@code DEFER} → 建单「已受理待唤醒」＋ {@code waitReason} ＋ {@code wakeEtaTs}，
 *       发布延后事件只落库不组批；可估计唤醒时刻的注册定时唤醒
 *       （{@link AdmissionWakeScheduler}），闸门类延后由 {@code WeatherClearWaker} 兜住；</li>
 *   <li>{@code REJECT} → 不建单，返回 {@code accepted=false} ＋ 判定链原因码；</li>
 *   <li>{@code MANUAL} → 不建单，返回 {@code reasonCode=INCOMPLETE}（转人工补录）。</li>
 * </ul>
 *
 * <p>订单暂存进程内（每单一份 {@link OrderView}）：{@code order_task} 落库由
 * {@code dispatch.queue.DispatchTrigger} 完成，本类只维护内存视图与 {@code task_log} 留痕。
 * 重复单判定的既有订单以 {@code order_task} 落库为准（见 {@link #snapshotPriorOrders}）。
 */
@Service
public class OrderAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdmissionService.class);

    /** AIS MMSI 为 9 位十进制数字。 */
    private static final Pattern MMSI_PATTERN = Pattern.compile("\\d{9}");

    private final TaskLogRecorder taskLogRecorder;

    private final ApplicationEventPublisher eventPublisher;

    private final AdmissionInputAssembler inputAssembler;

    private final AdmissionWakeScheduler wakeScheduler;

    /** 既有订单（重复单判定的数据源）：以 {@code order_task} 落库为准，不另设内存登记。 */
    private final OrderTaskMapper orderTaskMapper;

    /** 已受理订单，key = orderId。 */
    private final Map<String, OrderView> orders = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    public OrderAdmissionService(TaskLogRecorder taskLogRecorder,
            ApplicationEventPublisher eventPublisher,
            AdmissionInputAssembler inputAssembler,
            AdmissionWakeScheduler wakeScheduler,
            OrderTaskMapper orderTaskMapper) {
        this.taskLogRecorder = taskLogRecorder;
        this.eventPublisher = eventPublisher;
        this.inputAssembler = inputAssembler;
        this.wakeScheduler = wakeScheduler;
        this.orderTaskMapper = orderTaskMapper;
    }

    /**
     * 接入一笔下单请求：基本校验 → 接单判定链 → 四分流。
     *
     * @param request 下单请求
     * @return 受理结果（含原因码与延后信息）
     */
    public OrderSubmitResponse submit(OrderSubmitRequest request) {
        long nowMs = System.currentTimeMillis();

        RejectReason reason = validateBasic(request, nowMs);
        if (reason != null) {
            log.info("下单被拒（基本校验）：mmsi={} 原因={}",
                    request == null ? null : request.shipMmsi(), reason.code());
            return OrderSubmitResponse.rejected(reason.code(), reason.message());
        }

        // HIGH.17：mmsi 只归一一次，校验、判定、事件用同一个值
        // 11-05 序号 1：归一已下沉到 OrderSubmitRequest 的紧凑构造器，此处 trim 为幂等兜底
        String mmsi = request.shipMmsi().trim();
        // HIGH.18：优先级先过枚举，事件与落库统一写枚举名，不透传原始请求串
        OrderPriority priority = OrderPriority.fromCode(request.priority());

        String orderId = nextOrderId(nowMs);
        AdmissionPipeline.OrderRequest pipelineRequest = new AdmissionPipeline.OrderRequest(
                orderId, mmsi, request.goodsType(), request.goodsWeight(),
                request.expectedTimeMs(), priority.name(), null, request.cargoNestId());

        AdmissionPipeline.Input input = inputAssembler.assemble(
                pipelineRequest, nowMs, snapshotPriorOrders());
        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        log.info("接单判定：orderId={} mmsi={} 出口={} 原因={} 未判定判据={} 细节={}",
                orderId, mmsi, decision.outcome(), decision.reason(),
                decision.skippedCriteria(), decision.detail());

        return switch (decision.outcome()) {
            case ACCEPT_TRIGGER_SCHEDULE -> acceptAndSchedule(request, pipelineRequest, priority,
                    decision, orderId, nowMs);
            case DEFER -> defer(request, pipelineRequest, decision, orderId, nowMs);
            case REJECT -> OrderSubmitResponse.rejected(decision.reason().name(), decision.detail());
            case MANUAL -> OrderSubmitResponse.rejected(decision.reason().name(), decision.detail());
        };
    }

    /** 受理并调度：建单「待受理」，交给 DispatchTrigger 落库并驱动调度主链。 */
    private OrderSubmitResponse acceptAndSchedule(OrderSubmitRequest request,
            AdmissionPipeline.OrderRequest pipelineRequest, OrderPriority priority,
            AdmissionPipeline.Decision decision, String orderId, long nowMs) {
        OrderView view = new OrderView(
                orderId,
                OrderStatus.PENDING_ACCEPT,
                nowMs,
                1L,
                null,
                null,
                null,
                null,
                decision.etaTsMs());

        orders.put(orderId, view);
        taskLogRecorder.record(orderId, "订单接入", null, OrderStatus.PENDING_ACCEPT.getValue(), nowMs);

        log.info("建单成功：orderId={} mmsi={} 载重={}kg 优先级={} 状态={} deadline={} etaTs={}",
                orderId, pipelineRequest.mmsi(), request.goodsWeight(),
                priority, OrderStatus.PENDING_ACCEPT.getValue(), decision.deadlineMs(),
                decision.etaTsMs());

        // 先发布版本 1，保证实时端按「待受理 → 后续状态」的顺序看到完整时间线。
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));

        // B0：发布「订单已受理」事件（waitReason=null → 完整主链），由 DispatchTrigger 驱动
        // 落库 → 受理 → 组批调度；priority 写枚举名（HIGH.18），不透传原始请求串。
        // 11-05 序号 2：expectedTimeMs 改传系统算出的 deadline（不再是客户端提交值），
        // etaTs 传未派单阶段的下界，两者都由 DispatchTrigger 落库。
        eventPublisher.publishEvent(new OrderSubmittedEvent(
                orderId, pipelineRequest.mmsi(), request.goodsWeight(), request.goodsType(),
                decision.deadlineMs(), priority.name(), nowMs, null, null, decision.etaTsMs()));

        return OrderSubmitResponse.accepted(view);
    }

    /** 延后待唤醒：建单「已受理待唤醒」，落库但不组批；可估计唤醒时刻的注册定时唤醒。 */
    private OrderSubmitResponse defer(OrderSubmitRequest request,
            AdmissionPipeline.OrderRequest pipelineRequest, AdmissionPipeline.Decision decision,
            String orderId, long nowMs) {
        WaitReason waitReason = toWaitReason(decision.reason());
        Long wakeEtaTs = decision.wakeAtMs() > 0 ? decision.wakeAtMs() : null;

        OrderView view = new OrderView(
                orderId,
                OrderStatus.ACCEPTED_WAIT_WAKE,
                nowMs,
                1L,
                waitReason,
                wakeEtaTs,
                null,
                null,
                decision.etaTsMs());

        orders.put(orderId, view);
        // opType 沿用 B3 既有口径：闸门类延后记「闸门熔断-延后」，其余延后记「接单延后」，
        // 保证 task_log 检索口径与 gateCutoff 旧链路一致
        String deferOpType = decision.reason() == AdmissionPipeline.Reason.GATE_CUTOFF
                ? "闸门熔断-延后" : "接单延后";
        taskLogRecorder.record(orderId, deferOpType, null,
                OrderStatus.ACCEPTED_WAIT_WAKE.getValue(), nowMs);

        log.info("接单延后：orderId={} mmsi={} 原因={} 等待={} 唤醒时刻={} deadline={} etaTs={}",
                orderId, pipelineRequest.mmsi(), decision.reason(), waitReason, wakeEtaTs,
                decision.deadlineMs(), decision.etaTsMs());

        eventPublisher.publishEvent(new OrderStateChangedEvent(view));

        // 延后事件：DispatchTrigger 只落库为「已受理待唤醒」，不受理、不组批
        eventPublisher.publishEvent(new OrderSubmittedEvent(
                orderId, pipelineRequest.mmsi(), request.goodsWeight(), request.goodsType(),
                decision.deadlineMs(), pipelineRequest.priority(), nowMs,
                waitReason.name(), wakeEtaTs, decision.etaTsMs()));

        // 定时唤醒：tEnter−wakeMargin（等船）与 suspendRetrySec 重评点（数据不足／未进段）
        // 都给出确定时刻；事件型唤醒（闸门解除／货就绪）由既有链路兜住，wakeAtMs=0 不注册
        if (decision.wakeAtMs() > 0) {
            wakeScheduler.scheduleWake(orderId, decision.wakeAtMs());
        }

        return OrderSubmitResponse.deferred(view, decision.reason().name(), decision.detail(),
                waitReason.name(), wakeEtaTs);
    }

    /** 判定链原因 → 展示层等待原因（口径：等船／等货／闸门或数据）。 */
    private static WaitReason toWaitReason(AdmissionPipeline.Reason reason) {
        return switch (reason) {
            case SHIP_OUT_OF_SEGMENT -> WaitReason.WAIT_SHIP;
            case GOODS_NOT_READY -> WaitReason.WAIT_CARGO;
            case DATA_STALE, GATE_CUTOFF -> WaitReason.GATE_OR_DATA;
            default -> WaitReason.GATE_OR_DATA;
        };
    }

    /** 订单列表快照，按建单时间＋订单号稳定排序。 */
    public List<OrderView> listOrders() {
        return orders.values().stream()
                .sorted(Comparator.comparingLong(OrderView::eventTs).thenComparing(OrderView::orderId))
                .toList();
    }

    /** 已受理订单数。 */
    public int orderCount() {
        return orders.size();
    }

    /**
     * 状态事务提交后更新 REST 快照，使 GET /api/orders 与 order_state 共用同一版本。
     * fallbackExecution=true 兼容无事务发布（建单版本 1）与模块单测。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        merge(event.view());
    }

    /**
     * 飞行态变化也并入 REST 快照：order_state 与 GET /api/orders 共用同一
     * stateVersion，避免重连后低版本快照把飞行阶段盖回「已派发」。
     */
    @EventListener
    public void onFlightPhaseChanged(FlightPhaseChangedEvent event) {
        merge(event.view());
    }

    private void merge(OrderView next) {
        orders.compute(next.orderId(), (orderId, current) ->
                current == null || next.stateVersion() > current.stateVersion() ? next : current);
    }

    /**
     * 既有订单快照（重复单判定输入）：<b>以 {@code order_task} 落库为准</b>，只取非终止态
     * 订单（终止态天然被 {@code terminal=false} 语义排除，无需查出再标）。
     *
     * <p><b>为什么查库而不是进程内登记</b>：判定链口径是「窗口内存在同船同货类同重量的
     * <b>非终止订单</b>」——订单是否非终止以 {@code order_task.status} 为权威；内存登记会
     * 在重启后丢失、且与落库状态漂移（如订单已被人工终止但登记还在）。
     * 本查询发生在本单落库之前，不含自身，无自匹配问题。
     */
    private List<AdmissionPipeline.PriorOrder> snapshotPriorOrders() {
        List<String> terminalValues = List.of(
                OrderStatus.REJECTED.getValue(), OrderStatus.FAILED.getValue(),
                OrderStatus.ABORTED.getValue());
        List<OrderTaskEntity> rows = orderTaskMapper.selectList(
                new LambdaQueryWrapper<OrderTaskEntity>()
                        .notIn(OrderTaskEntity::getStatus, terminalValues));
        return rows.stream()
                .map(row -> new AdmissionPipeline.PriorOrder(
                        row.getShipMmsi(), row.getGoodsType(),
                        row.getGoodsWeight() == null ? 0.0 : row.getGoodsWeight().doubleValue(),
                        row.getCreateTime() == null ? 0L
                                : row.getCreateTime().atZone(ZoneId.systemDefault())
                                        .toInstant().toEpochMilli(),
                        false))
                .toList();
    }

    /**
     * 基本校验（请求级形式合法性；业务判定链在 {@link AdmissionPipeline}）。
     *
     * @return 通过返回 {@code null}；否则返回第一条命中的拒绝原因
     */
    private RejectReason validateBasic(OrderSubmitRequest request, long nowMs) {
        if (request == null) {
            return RejectReason.REQUEST_MISSING;
        }
        if (request.shipMmsi() == null || request.shipMmsi().isBlank()) {
            return RejectReason.MMSI_MISSING;
        }
        // 首尾空白先 trim 再校验：表单或 CSV 粘贴常带空格，仍属良构输入
        if (!MMSI_PATTERN.matcher(request.shipMmsi().trim()).matches()) {
            return RejectReason.MMSI_MALFORMED;
        }
        if (request.goodsWeight() == null || !(request.goodsWeight() > 0.0)
                || !Double.isFinite(request.goodsWeight())) {
            return RejectReason.GOODS_WEIGHT_INVALID;
        }
        // 11-05 序号 1：goods_type 是 VARCHAR(64)，超长在库层要么被静默截断（两种货型被判成同一类），
        // 要么直接报错（一次下单变成 500）。在这里拦下，代价是前端改一行提示，不是查一次库。
        if (request.goodsType() != null
                && request.goodsType().length() > OrderSubmitRequest.GOODS_TYPE_MAX_LENGTH) {
            return RejectReason.GOODS_TYPE_TOO_LONG;
        }
        try {
            OrderPriority.fromCode(request.priority());
        } catch (IllegalArgumentException e) {
            return RejectReason.PRIORITY_INVALID;
        }
        if (request.expectedTimeMs() != null && request.expectedTimeMs() <= nowMs) {
            return RejectReason.EXPECTED_TIME_INVALID;
        }
        return null;
    }

    /** 订单号：{@code ORD-<毫秒时间戳>-<序号>}，进程内唯一、可按时间排序。 */
    private String nextOrderId(long nowMs) {
        return "ORD-" + nowMs + "-" + sequence.incrementAndGet();
    }

    /**
     * 基本校验拒绝原因码。
     *
     * <p>原因码用英文常量名，便于前端分支与日志检索；{@link #message()} 为中文说明。
     * 接单判定链的业务原因码见 {@link AdmissionPipeline.Reason}。
     */
    public enum RejectReason {

        /** 请求体缺失 */
        REQUEST_MISSING("请求体缺失"),

        /** 未提供船舶 MMSI */
        MMSI_MISSING("船舶 MMSI 缺失"),

        /** MMSI 格式不合法 */
        MMSI_MALFORMED("船舶 MMSI 格式不合法（应为 9 位数字）"),

        /** 载重不合法 */
        GOODS_WEIGHT_INVALID("物资载重必须为正数（kg）"),

        /** 货型超长（列宽 64） */
        GOODS_TYPE_TOO_LONG("物资类型过长（上限 " + OrderSubmitRequest.GOODS_TYPE_MAX_LENGTH + " 字符）"),

        /** 优先级不合法 */
        PRIORITY_INVALID("优先级取值不合法（NORMAL/URGENT/EMERGENCY）"),

        /** 期望送达时间不合法 */
        EXPECTED_TIME_INVALID("期望送达时间必须晚于当前时刻");

        private final String message;

        RejectReason(String message) {
            this.message = message;
        }

        /** 原因码（枚举名）。 */
        public String code() {
            return name();
        }

        /** 中文说明。 */
        public String message() {
            return message;
        }
    }
}
