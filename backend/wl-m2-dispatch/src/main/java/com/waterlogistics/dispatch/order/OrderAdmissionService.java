package com.waterlogistics.dispatch.order;

import com.waterlogistics.algo.cargo.CargoNestChoice;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.candidate.CargoNestDecisionService;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import com.waterlogistics.dispatch.taskstate.FlightPhaseChangedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * 进程实例码：启动时取一次，参与订单号，防「重启／多实例同毫秒撞号」（MED-BUG.32）。
     *
     * <p>取 8 位十六进制（UUID 前 8 位）而不是完整 UUID：{@code order_id} 只有
     * {@code VARCHAR(64)}，且订单号要能被人工在日志里扫读；8 位足够把碰撞概率压到
     * 与完整 UUID 同一量级（16^8 ≈ 4.3e9 分之一，且只在同一毫秒叠加）。
     */
    private static final String INSTANCE_TAG =
            UUID.randomUUID().toString().substring(0, 8);

    /**
     * 订单列表默认返回条数（LOW-PERF.1）。
     *
     * <p>取 200 而不是「全部」：演示与联调时单屏最多看几十条，200 足够；而它在最坏情况下
     * 也只是把一次响应体控制在几百 KB，不会把一个状态推送变成整包全量。
     */
    static final int DEFAULT_LIST_LIMIT = 200;

    /** 订单列表硬上限：再大就不是「看列表」，而是「导出」，该走专门的导出接口。 */
    static final int MAX_LIST_LIMIT = 1000;

    private final TaskLogRecorder taskLogRecorder;

    private final ApplicationEventPublisher eventPublisher;

    private final AdmissionInputAssembler inputAssembler;

    private final AdmissionWakeScheduler wakeScheduler;

    /** 既有订单（重复单判定的数据源）：以 {@code order_task} 落库为准，不另设内存登记。 */
    private final OrderTaskMapper orderTaskMapper;

    /**
     * 货巢决策服务：下单时刻由系统选定货物所在的机巢。
     *
     * <p>为 null 时不选巢（兼容构造器路径），下单行为与改造前完全一致。
     */
    private final CargoNestDecisionService cargoNestDecisionService;

    /** 已受理订单，key = orderId。 */
    private final Map<String, OrderView> orders = new ConcurrentHashMap<>();

    /**
     * 事务内合并前的视图快照（MED-BUG.31）：事务回滚时按它把 {@link #orders} 退回去。
     *
     * <p>用实例字段而不是静态：同一个测试上下文里可能有多个本类实例，静态会串号。
     * 内容在事务结束时（无论提交还是回滚）清空，不跨事务累积。
     */
    private final ThreadLocal<Map<String, OrderView>> mergeRollback = new ThreadLocal<>();

    private final AtomicLong sequence = new AtomicLong();

    @Autowired
    public OrderAdmissionService(TaskLogRecorder taskLogRecorder,
            ApplicationEventPublisher eventPublisher,
            AdmissionInputAssembler inputAssembler,
            AdmissionWakeScheduler wakeScheduler,
            OrderTaskMapper orderTaskMapper,
            CargoNestDecisionService cargoNestDecisionService) {
        this.taskLogRecorder = taskLogRecorder;
        this.eventPublisher = eventPublisher;
        this.inputAssembler = inputAssembler;
        this.wakeScheduler = wakeScheduler;
        this.orderTaskMapper = orderTaskMapper;
        this.cargoNestDecisionService = cargoNestDecisionService;
    }

    /**
     * 兼容构造器：不带货巢决策服务。
     *
     * <p>保留给既有单测与旧调用点。没有决策服务时系统不选巢，货巢为空，
     * 订单走「无货巢」旧口径，行为与改造前逐字一致。
     *
     * <p>本构造器<b>不参与</b> Spring 装配：类上有两个构造器时，容器无法判断该用哪个，
     * 只能报错「找不到默认构造器」，因此生产注入固定走上面那个标注了
     * {@code @Autowired} 的构造器。
     */
    public OrderAdmissionService(TaskLogRecorder taskLogRecorder,
            ApplicationEventPublisher eventPublisher,
            AdmissionInputAssembler inputAssembler,
            AdmissionWakeScheduler wakeScheduler,
            OrderTaskMapper orderTaskMapper) {
        this(taskLogRecorder, eventPublisher, inputAssembler, wakeScheduler, orderTaskMapper, null);
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
        // 货巢来源优先级：客户端显式指定 > 系统在下单时刻决策 > 无货巢（退回旧口径）。
        String cargoNestId = request.hasCargoNest()
                ? request.cargoNestId()
                : decideCargoNest(orderId, mmsi, request.goodsWeight(), nowMs);
        AdmissionPipeline.OrderRequest pipelineRequest = new AdmissionPipeline.OrderRequest(
                orderId, mmsi, request.goodsType(), request.goodsWeight(),
                request.expectedTimeMs(), priority.name(), null, cargoNestId);

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

    /**
     * 货巢决策：下单时刻由系统选定一个能装货、也停得下机的机巢。
     *
     * <p><b>为什么选不出时仍让下单成立</b>：选巢依赖船位快照与机巢台账，
     * 缺数据时若直接拒绝下单，等于把台账问题变成业务阻断。因此退回旧口径
     * （订单不经货到巢关），并在订单轨迹上留痕，让「未指定货巢」这件事可见可诊断。
     * 落选原因见决策服务与调度日志。
     *
     * @param orderId       订单号（用于订单轨迹留痕）
     * @param mmsi          目标船 MMSI
     * @param goodsWeightKg 本单货物载重，kg
     * @param nowMs         当前时刻，Unix 毫秒
     * @return 选中的货巢编号；未命中时为 null
     */
    private String decideCargoNest(String orderId, String mmsi, Double goodsWeightKg, long nowMs) {
        if (cargoNestDecisionService == null) {
            return null;
        }
        CargoNestChoice choice = cargoNestDecisionService.decide(mmsi, goodsWeightKg);
        if (!choice.hit()) {
            taskLogRecorder.record(orderId, "货巢未选定", null,
                    OrderStatus.PENDING_ACCEPT.getValue(), nowMs);
            return null;
        }
        taskLogRecorder.record(orderId, "货巢选定", null,
                OrderStatus.PENDING_ACCEPT.getValue(), nowMs);
        return choice.nestId();
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
                decision.etaTsMs(),
                pipelineRequest.cargoNestId());

        orders.put(orderId, view);
        taskLogRecorder.record(orderId, "订单接入", null, OrderStatus.PENDING_ACCEPT.getValue(), nowMs);

        log.info("建单成功：orderId={} mmsi={} 载重={}kg 优先级={} 状态={} deadline={} etaTs={}",
                orderId, pipelineRequest.mmsi(), request.goodsWeight(),
                priority, OrderStatus.PENDING_ACCEPT.getValue(), decision.deadlineMs(),
                decision.etaTsMs());

        // 先发布版本 1，保证实时端按「待受理 → 后续状态」的顺序看到完整时间线。
        publishOrCompensate(orderId, new OrderStateChangedEvent(view), "状态广播");

        // B0：发布「订单已受理」事件（waitReason=null → 完整主链），由 DispatchTrigger 驱动
        // 落库 → 受理 → 组批调度；priority 写枚举名（HIGH.18），不透传原始请求串。
        // 11-05 序号 2：expectedTimeMs 改传系统算出的 deadline（不再是客户端提交值），
        // etaTs 传未派单阶段的下界，cargoNestId 传货巢（落 order_task.cargo_nest_id），
        // 三者都由 DispatchTrigger 落库。
        publishOrCompensate(orderId, new OrderSubmittedEvent(
                orderId, pipelineRequest.mmsi(), request.goodsWeight(), request.goodsType(),
                decision.deadlineMs(), priority.name(), nowMs, null, null, decision.etaTsMs(),
                pipelineRequest.cargoNestId()), "受理落库");

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
                decision.etaTsMs(),
                pipelineRequest.cargoNestId());

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

        publishOrCompensate(orderId, new OrderStateChangedEvent(view), "状态广播");

        // 延后事件：DispatchTrigger 只落库为「已受理待唤醒」，不受理、不组批
        // 11-05 序号 2：cargoNestId 一并带出——延后单同样要能在库里看见「货在哪个巢」，
        // 否则唤醒后被重判时货巢丢失，只能退回「离船最近巢」，与用户下单时的意图不符
        publishOrCompensate(orderId, new OrderSubmittedEvent(
                orderId, pipelineRequest.mmsi(), request.goodsWeight(), request.goodsType(),
                decision.deadlineMs(), pipelineRequest.priority(), nowMs,
                waitReason.name(), wakeEtaTs, decision.etaTsMs(), pipelineRequest.cargoNestId()),
                "延后落库");

        // 定时唤醒：tEnter−wakeMargin（等船）与 suspendRetrySec 重评点（数据不足／未进段）
        // 都给出确定时刻；事件型唤醒（闸门解除／货就绪）由既有链路兜住，wakeAtMs=0 不注册
        if (decision.wakeAtMs() > 0) {
            wakeScheduler.scheduleWake(orderId, decision.wakeAtMs());
        }

        return OrderSubmitResponse.deferred(view, decision.reason().name(), decision.detail(),
                waitReason.name(), wakeEtaTs);
    }

    /**
     * 发布事件；失败时先把内存视图退回去，再把异常原样抛出（MEDIUM.30）。
     *
     * <p><b>要解决的错配</b>：{@code publishEvent} 是同步的，{@code DispatchTrigger} 在下游落库
     * 失败时会回滚自己的事务并重新抛出。此前本类不接这个异常，于是——订单已 {@code put} 进
     * {@link #orders}、{@code task_log} 也记了「订单接入」，而库里没这一行；调用方拿到 500。
     * 三条真相各说一套，事后按订单号查会查到一个只存在于内存里的单。
     *
     * <p><b>为什么不改成「先落库再进内存」</b>：状态广播必须先发版本 1，实时端才能按
     * 「待受理 → 后续状态」排时间线；把顺序倒过去会把前端的状态序列弄成倒序。
     * 也不改成「失败返回 rejected」：落库失败是基础设施故障，不是业务判定，
     * 用 200 ＋ {@code reasonCode} 包装它会让上游以为「这单被规则拒了、可以改参数重提」。
     *
     * <p><b>修完的口径</b>：内存视图与调用方看到的结果一致（都失败）；
     * {@code task_log} 里那条「订单接入」保持不动——它是留痕，不是真相来源，
     * 而以 {@code order_task} 为准的既有约定没变。
     *
     * @param orderId 订单号（补偿时要按它从内存视图里摘掉）
     * @param event   待发布事件
     * @param phase   阶段名，仅用于日志定位是「状态广播」还是「受理/延后落库」失败
     */
    private void publishOrCompensate(String orderId, Object event, String phase) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            orders.remove(orderId);
            log.warn("订单事件发布失败，内存视图已回退：orderId={} 阶段={}（task_log 中该单的接入留痕保留）",
                    orderId, phase, e);
            throw e;
        }
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

    /**
     * 订单列表快照，带状态过滤与条数上限（11-05 序号 2 · LOW-PERF.1）。
     *
     * <p><b>为什么必须有上限</b>：改前 {@code GET /api/orders} 一次返回全部订单。进程内
     * {@link #orders} 只增不减（内存视图没有淘汰），演示跑一天就有几百上千条；
     * 前端列表页一次渲染全部、每次状态推送又整包重发，请求体越滚越大，最后卡的是浏览器
     * 而不是服务端——这类问题在演示现场才会显形，而那时已经没有改代码的时间。
     *
     * <p><b>为什么过滤放在服务层而不是 Controller</b>：{@link #orders} 的 owner 是本类，
     * 「最新 N 条」的语义要贴着数据看；放在 Controller 里做，等于把遍历整表当成查询的代价
     * 留给每个调用方。
     *
     * @param status 状态过滤：接受落库口径的中文状态词（如 {@code 待调度}）或枚举名
     *               （如 {@code WAIT_DISPATCH}，忽略大小写）；空白表示不过滤。
     *               <b>无法识别时返回空列表</b>：查询参数拼错是客户端错误，回「全部」
     *               会让人误以为过滤生效了
     * @param limit  条数上限；{@code null} 取 {@value #DEFAULT_LIST_LIMIT}，
     *               超出 {@value #MAX_LIST_LIMIT} 按上限截断（防止一次拉全库当导出用）
     * @return 按建单时间升序的订单视图
     */
    public List<OrderView> listOrders(String status, Integer limit) {
        int cap = limit == null ? DEFAULT_LIST_LIMIT
                : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        List<OrderView> sorted = listOrders();
        if (status == null || status.isBlank()) {
            return sorted.size() <= cap ? sorted : sorted.subList(0, cap);
        }
        OrderStatus wanted = parseStatus(status);
        if (wanted == null) {
            log.warn("订单列表状态过滤值无法识别，返回空列表：status={}", status);
            return List.of();
        }
        List<OrderView> filtered = sorted.stream()
                .filter(view -> view.orderStatus() == wanted)
                .toList();
        return filtered.size() <= cap ? filtered : filtered.subList(0, cap);
    }

    /**
     * 解析状态过滤值：先按落库中文词，再按枚举名（忽略大小写）。
     *
     * @param raw 原始取值
     * @return 枚举；无法识别返回 {@code null}
     */
    private static OrderStatus parseStatus(String raw) {
        String value = raw.trim();
        for (OrderStatus status : OrderStatus.values()) {
            if (status.getValue().equals(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
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
     *
     * <h3>MED-BUG.31：内存视图不得超前库（回滚补偿），但也不能延后到提交后</h3>
     * <p>缺陷是真实的：{@code FlightPhaseAdvancer} 把「写 {@code order_task.status}」与
     * 「发本事件」放在同一事务里，若事件在事务<b>内</b>就把视图改掉，事务随后回滚
     * （约束冲突、占位释放失败后的后续写库失败），{@code GET /api/orders} 与实时端已经
     * 显示「去程中」而库里还是「已派发」，且没有任何补偿把它改回去。
     *
     * <p><b>为什么不用 {@code @TransactionalEventListener(AFTER_COMMIT)}</b>（那是本类另一条
     * 监听 {@link #onOrderStateChanged} 的做法）：AFTER_COMMIT 只在事务<b>真正提交</b>后触发，
     * 于是「长事务内推进多拍」时视图一直停在旧状态——{@code @Transactional} 的集成测试是最极端的
     * 例子（外层事务永不提交，6 条真库用例的视图断言会全部看不到更新），但生产里
     * 批量补算事务同样会遇到。视图是给前端读的，它应该「立即反映最新一次已发出的状态」，
     * 而正确性靠回滚补偿守住，不靠延后。
     *
     * <p>补偿口径：在事务内第一次合并某单前，记下它当时的视图；事务回滚时按记录逐单退回
     * （原本没有该单的，直接移除）。用「事务内首见值」而不是「每次合并的前一个值」，
     * 是因为同一事务可能推进多拍，逐次回退只能退回上一拍、退不回事前状态。
     */
    @EventListener
    public void onFlightPhaseChanged(FlightPhaseChangedEvent event) {
        mergeWithRollback(event.view());
    }

    /**
     * 合并视图，并在事务回滚时退回合并前的状态。
     *
     * @param next 新视图
     */
    private void mergeWithRollback(OrderView next) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务（单测直调、旁路调用）：没有回滚风险，直接合并
            merge(next);
            return;
        }
        Map<String, OrderView> rollback = mergeRollback.get();
        if (rollback == null) {
            rollback = new HashMap<>();
            mergeRollback.set(rollback);
            Map<String, OrderView> recorded = rollback;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            recorded.forEach((orderId, before) -> {
                                if (before == null) {
                                    orders.remove(orderId);
                                } else {
                                    orders.put(orderId, before);
                                }
                            });
                            log.info("事务回滚，内存订单视图已回退 {} 单", recorded.size());
                        }
                    } finally {
                        recorded.clear();
                        mergeRollback.remove();
                    }
                }
            });
        }
        rollback.putIfAbsent(next.orderId(), orders.get(next.orderId()));
        merge(next);
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
     *
     * <p>11-05 序号 2：一并带出 {@code cargo_nest_id}——重复单判据已从「同 MMSI ＋ 同货型」
     * 改为「同货巢 ＋ 同货型 ＋ 时间窗」，没有这一列就没有判据本身。
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
                        false,
                        row.getCargoNestId()))
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

    /**
     * 订单号：{@code ORD-<毫秒时间戳>-<实例码>-<序号>}，可直接按时间排序。
     *
     * <p><b>MED-BUG.32：为什么不是「时间戳 ＋ 进程内序号」</b>。{@code order_id} 是
     * {@code order_task} 的<b>主键</b>，撞号的后果不是「两条重单」而是第二条直接插入失败
     * （{@code Duplicate entry}），整笔下单变成 500。原实现只有毫秒戳与
     * {@link #sequence}，两类场景必撞：
     * <ul>
     *   <li><b>重启</b>：{@code sequence} 归零，重启后同一毫秒内下的第一笔就与重启前那笔同号；</li>
     *   <li><b>多实例</b>：两台实例各自从 1 开始，同一毫秒各下一单就同号。</li>
     * </ul>
     * 加静态实例码（进程启动时取一次 UUID 前缀）后，碰撞概率降到与 UUID 同级，
     * 而时间戳仍在前缀，运维按订单号排序、按日志检索的既有习惯不受影响。
     *
     * @param nowMs 下单时刻，Unix 毫秒
     * @return 订单号（长度约 30，远小于 {@code order_id} 的 {@code VARCHAR(64)}）
     */
    private String nextOrderId(long nowMs) {
        return "ORD-" + nowMs + "-" + INSTANCE_TAG + "-" + sequence.incrementAndGet();
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
