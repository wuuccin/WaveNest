package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单接入与基本校验（M2 · 卢肄文 · 模块二功能 1）。
 *
 * <p><b>今天到哪为止</b>：完成「接入 ＋ 基本校验 ＋ 建单」，订单落到
 * {@link OrderStatus#PENDING_ACCEPT}（待受理）为止。后续状态推进
 * （待受理 → 待调度 → 已派发…）属 {@code queue/gate} 子包（张睿哲），
 * <b>本类不越界推进状态机</b>，只把建单事件交给 {@link TaskLogRecorder}。
 *
 * <p><b>完整受理判定（含四类唤醒原因）排期顺延</b>：{@code 23} §3 的
 * 「已受理待唤醒」需要等船／等货／闸门熔断／数据不足四类判据。闸门状态已由
 * {@code dispatch.gate} 的 {@code DispatchGateService}（张睿哲）提供，
 * 但「等船／等货」还需船货状态与任务上下文，故完整受理判定仍顺延
 * ——这正是「基本校验」与「受理判定」的分界，避免与 {@code queue} 的状态机职责重叠。
 *
 * <p>订单暂存进程内（每单一份 {@link OrderView}）：{@code order_task} 落库由
 * {@code dispatch.queue}（张睿哲）推进状态机时经 M6 完成，本类只维护内存视图，
 * 并把建单事件交给 {@link TaskLogRecorder} 落到 {@code task_log}。
 */
@Service
public class OrderAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdmissionService.class);

    /** AIS MMSI 为 9 位十进制数字。 */
    private static final Pattern MMSI_PATTERN = Pattern.compile("\\d{9}");

    private final TaskLogRecorder taskLogRecorder;

    private final ApplicationEventPublisher eventPublisher;

    /** 已受理订单，key = orderId。 */
    private final Map<String, OrderView> orders = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    public OrderAdmissionService(TaskLogRecorder taskLogRecorder,
            ApplicationEventPublisher eventPublisher) {
        this.taskLogRecorder = taskLogRecorder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 接入一笔下单请求：先做基本校验，通过则建单为「待受理」。
     *
     * @param request 下单请求
     * @return 受理结果（含原因码）
     */
    public OrderSubmitResponse submit(OrderSubmitRequest request) {
        long nowMs = System.currentTimeMillis();

        RejectReason reason = validate(request, nowMs);
        if (reason != null) {
            log.info("下单被拒：mmsi={} 原因={}", request == null ? null : request.shipMmsi(), reason.code());
            return OrderSubmitResponse.rejected(reason.code(), reason.message());
        }

        String orderId = nextOrderId(nowMs);

        // 待受理：接单判定尚未完成；waitReason 仅「已受理待唤醒」有值，此处必须为 null
        OrderView view = new OrderView(
                orderId,
                OrderStatus.PENDING_ACCEPT,
                nowMs,
                1L,
                null,
                null,
                null,
                null);

        orders.put(orderId, view);
        taskLogRecorder.record(orderId, "订单接入", null, OrderStatus.PENDING_ACCEPT.getValue(), nowMs);

        log.info("建单成功：orderId={} mmsi={} 载重={}kg 优先级={} 状态={}",
                orderId, request.shipMmsi(), request.goodsWeight(),
                OrderPriority.fromCode(request.priority()), OrderStatus.PENDING_ACCEPT.getValue());

        // 先发布版本 1，保证实时端按「待受理 → 后续状态」的顺序看到完整时间线。
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));

        // B0：发布「订单已受理」事件，由 DispatchTrigger 驱动落库 → 受理 → 组批调度
        eventPublisher.publishEvent(new OrderSubmittedEvent(
                orderId, request.shipMmsi(), request.goodsWeight(), request.goodsType(),
                request.expectedTimeMs(), request.priority(), nowMs));

        return OrderSubmitResponse.accepted(view);
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
        OrderView next = event.view();
        orders.compute(next.orderId(), (orderId, current) ->
                current == null || next.stateVersion() > current.stateVersion() ? next : current);
    }

    /**
     * 基本校验（{@code 80-A组算法方案} §3.1 判定链的简化落地）。
     *
     * @return 通过返回 {@code null}；否则返回第一条命中的拒绝原因
     */
    private RejectReason validate(OrderSubmitRequest request, long nowMs) {
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
     * 拒绝原因码（受理判定链的输出）。
     *
     * <p>原因码用英文常量名，便于前端分支与日志检索；{@link #message()} 为中文说明。
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
