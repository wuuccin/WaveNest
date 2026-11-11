package com.waterlogistics.dispatch.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.goods.GoodsLifecycleService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 任务队列与状态管理（模块二 功能 8，算法见 83 §6）。
 * 本类只负责订单状态机流转（枚举照 23 §3，写入方照 23 §8），不做业务判断。
 *
 * 状态一律用 wl-contract.state.OrderStatus（落库取 getValue() 中文状态词，
 * 与 order_task.status 列注释一致）；23 §9 禁用词由契约枚举天然规避。
 * 演示阶段（10-15）只实现主链：待受理 → 待调度 → 调度中 → 已派发。
 */
@Service
public class TaskQueueService {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    /**
     * 可重入队白名单（HIGH.28）：只允许「尚未起飞」的可回退态。
     * 已完成／终止态／飞行中（去程中／末段交接中／返航中）一律拒绝——飞行中回退会把
     * 正在执行的无人机直接置「空闲」，破坏 23 §7 无人机状态机。
     */
    /** 真实投放回执的交付凭证操作类型（D-02；写进 task_log 供审计与幂等核查）。 */
    public static final String OP_TYPE_DELIVERY_RECEIPT = "投放回执-交付确认";

    private static final Set<OrderStatus> REQUEUEABLE = EnumSet.of(
            OrderStatus.DISPATCHED, OrderStatus.DISPATCHING,
            OrderStatus.WAIT_DISPATCH, OrderStatus.ACCEPTED_WAIT_WAKE);

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DispatchGateService dispatchGateService;
    private final DroneMapper droneMapper;
    private final ApplicationEventPublisher eventPublisher;
    /** 货到巢关（11-05 序号 2）；老构造器为 null 时跳过（仅影响早期单测） */
    private final CargoNestGate cargoNestGate;
    @Autowired(required = false)
    private GoodsLifecycleService goodsLifecycleService;
    private final Map<String, AtomicLong> fallbackVersions = new ConcurrentHashMap<>();

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, null, event -> { }, null);
    }

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, ApplicationEventPublisher eventPublisher) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, null, eventPublisher, null);
    }

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, DroneMapper droneMapper,
            ApplicationEventPublisher eventPublisher) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, droneMapper, eventPublisher, null);
    }

    @Autowired
    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, DroneMapper droneMapper,
            ApplicationEventPublisher eventPublisher, CargoNestGate cargoNestGate) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.dispatchGateService = dispatchGateService;
        this.droneMapper = droneMapper;
        this.eventPublisher = eventPublisher;
        this.cargoNestGate = cargoNestGate;
    }

    /**
     * 受理：待受理 → 待调度（第 1 步受理判定，演示阶段默认满足派单前置门直接可派）。
     * 等船/等货分支（→ 已受理待唤醒 + 算法层 wakeReason 四类，M5 投影 WaitReason
     * 三值）16 号接入，此处留 TODO。
     */
    @Transactional
    public void accept(OrderTaskEntity order) {
        requireStatus(order, OrderStatus.PENDING_ACCEPT);
        transition(order, OrderStatus.PENDING_ACCEPT, OrderStatus.WAIT_DISPATCH, "受理", null);
    }

    /**
     * 批量取单入口（11-05 序号 2，张睿哲）：按待调度优先级取队首候选订单。
     *
     * <p>取单顺序由数据库完成：
     * {@code ORDER BY FIELD(priority,'EMERGENCY','URGENT','NORMAL'), expected_time, create_time}
     * —— 应急 > 加急 > 常规；同优先级按期望时间（旧的在前），再按建单时间（旧的在前）。
     * 注意 MySQL 升序下 NULL 排最前：expected_time 为空的订单在同优先级内最先被取到。</p>
     *
     * <p>本方法只读不改状态；调用方取到后自行走 {@link #startDispatch} 链路。</p>
     *
     * @param limit 单次最多取多少笔，范围 [1, 500]
     */
    public List<OrderTaskEntity> selectNext(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("selectNext limit 须在 [1,500] 内: " + limit);
        }
        return orderTaskMapper.selectList(new LambdaQueryWrapper<OrderTaskEntity>()
                .eq(OrderTaskEntity::getStatus, OrderStatus.WAIT_DISPATCH.getValue())
                .last("ORDER BY FIELD(priority,'EMERGENCY','URGENT','NORMAL'), expected_time, create_time LIMIT "
                        + limit));
    }

    /**
     * 组批调度：待调度 → 调度中（第 2 步起开始求解）。
     *
     * <p><b>B3 闸门联动</b>：组批入口查 {@link DispatchGateService#isOpen()}，
     * 气象熔断时新单转「已受理待唤醒」而非拒单（22 §3.2：熔断期间不拒单），
     * 待闸门解除后由重入队链路重新进入组批。
     */
    @Transactional
    public void startDispatch(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.WAIT_DISPATCH);

        if (!dispatchGateService.isOpen()) {
            // 熔断：延后到「已受理待唤醒」，不拒单
            transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.ACCEPTED_WAIT_WAKE,
                    "闸门熔断-延后", WaitReason.GATE_OR_DATA);
            return;
        }

        // 货到巢关（11-05 序号 2）：派单前锁巢时隙；货未到转 WAIT_CARGO，锁不到转 WAIT_RESOURCE。
        // 延后后由 CargoGateWaker 轮询放行/超时，orchestrate 见「已受理待唤醒」即结束本轮。
        if (cargoNestGate != null) {
            CargoNestGate.Decision gate = cargoNestGate.evaluate(orderId, order.getCargoNestId());
            if (gate == CargoNestGate.Decision.WAIT_CARGO) {
                transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.ACCEPTED_WAIT_WAKE,
                        "货到巢关-待货", WaitReason.WAIT_CARGO);
                return;
            }
            if (gate == CargoNestGate.Decision.WAIT_RESOURCE) {
                transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.ACCEPTED_WAIT_WAKE,
                        "货到巢关-锁巢失败", WaitReason.WAIT_RESOURCE);
                return;
            }
        }

        transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.DISPATCHING, "组批调度", null);
    }

    /**
     * 锁超时处置（11-05 序号 2 · 货到巢关）：「已受理待唤醒 + WAIT_CARGO」→
     * 「已受理待唤醒 + WAIT_RESOURCE」，并释放本单持有的巢时隙锁。
     *
     * <p>由 {@link CargoGateWaker} 在锁龄超过 {@code sys_param.cargoLockTimeoutSec}
     * 或锁已失踪时调用。状态没变（均为已受理待唤醒），变的是 wait_reason；
     * 条件更新影响行数≠1 时抛状态冲突（与 transition 同口径），事件照常发布，
     * 供告警链路消费（宋冰鑫序号 2 写 alert 行）。</p>
     */
    @Transactional
    public void markResourceWaiting(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.ACCEPTED_WAIT_WAKE);
        // 锁超时即锁失效，先释锁再置位；门关未启用（老链路）时无锁可释，releaseLockQuietly 内部兜底
        if (cargoNestGate != null) {
            cargoNestGate.releaseLockQuietly(orderId);
        }
        transition(order, OrderStatus.ACCEPTED_WAIT_WAKE, OrderStatus.ACCEPTED_WAIT_WAKE,
                "锁超时-转资源等待", WaitReason.WAIT_RESOURCE);
    }

    /**
     * 派发：调度中 → 已派发（第 4 步定案 + 第 5 步回填，关联无人机与起飞时刻）。
     *
     * <p><b>T1-6（2026-10-27 口径调整）</b>：会面点坐标回填与会面帧
     * （{@code RendezvousPlanCommittedEvent}）由编排器统一发布，此处不发——
     * 队列侧签名没有候选数与淘汰原因，再发一帧只会产生「一条全、一条空」的
     * 重复帧。原带 meetLon/meetLat 的五参重载无任何调用方（全仓仅编排器调
     * 本方法），属遗留死代码，已一并清除。
     *
     * <p><b>无人机状态回写（10-29 新增）</b>：派单即 L5 占位，
     * 同时把执行机置为「已预占」（23 §7：L5 占位成功至起飞前的窗口），
     * 还在原巢上（position_type=原巢，current_nest_id 不变）。后续起飞／返航／
     * 完成由 {@link com.waterlogistics.dispatch.taskstate.FlightPhaseAdvancer}
     * 在飞行阶段迁移时点继续推进。
     */
    @Transactional
    public void dispatch(String orderId, String droneId, LocalDateTime meetTime) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.DISPATCHING);
        // HIGH.29：派单字段回填走条件更新，只在订单仍处于「调度中」时生效，
        // 不用陈旧实体整行 updateById（防覆盖并发写入的 meet 点等字段）
        int filled = orderTaskMapper.update(null,
                Wrappers.<OrderTaskEntity>lambdaUpdate()
                        .eq(OrderTaskEntity::getOrderId, orderId)
                        .eq(OrderTaskEntity::getStatus, OrderStatus.DISPATCHING.getValue())
                        .set(OrderTaskEntity::getDroneId, droneId)
                        .set(OrderTaskEntity::getMeetTime, meetTime));
        if (filled != 1) {
            throw new IllegalStateException(
                    "订单 " + orderId + " 状态冲突：派单回填失败（订单已不在调度中）");
        }
        order.setDroneId(droneId);
        order.setMeetTime(meetTime);
        markDronePreoccupied(droneId);
        transition(order, OrderStatus.DISPATCHING, OrderStatus.DISPATCHED, "派发", null);
    }

    /**
     * 重入队（10-16 闭环第 4 环）：放弃会面后订单回到待调度，清空派单字段
     * （droneId/meetTime/meetLon/meetLat），原机不再关联。
     * 仅白名单内状态可回退（{@link #REQUEUEABLE}）；已在待调度时短路返回，
     * 不再重复写 task_log 与发状态事件。
     */
    @Transactional
    public void requeue(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在：" + orderId);
        }
        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (!REQUEUEABLE.contains(current)) {
            throw new IllegalStateException(
                "订单 " + orderId + " 当前状态 " + current.getValue() + " 不允许重入队");
        }
        // from == to：已在待调度，直接短路
        if (current == OrderStatus.WAIT_DISPATCH) {
            return;
        }
        // 放弃会面 → 执行机释放，状态回空闲（position_type 复位、current_nest_id 不变）
        if (order.getDroneId() != null) {
            markDroneIdle(order.getDroneId(), true);
        }
        // HIGH.29：清空派单字段同样走条件更新，只在状态未被并发推进时生效
        int cleared = orderTaskMapper.update(null,
                Wrappers.<OrderTaskEntity>lambdaUpdate()
                        .eq(OrderTaskEntity::getOrderId, orderId)
                        .eq(OrderTaskEntity::getStatus, current.getValue())
                        .set(OrderTaskEntity::getDroneId, null)
                        .set(OrderTaskEntity::getMeetTime, null)
                        .set(OrderTaskEntity::getMeetLon, null)
                        .set(OrderTaskEntity::getMeetLat, null));
        if (cleared != 1) {
            throw new IllegalStateException(
                    "订单 " + orderId + " 状态冲突：重入队清空派单字段失败");
        }
        order.setDroneId(null);
        order.setMeetTime(null);
        order.setMeetLon(null);
        order.setMeetLat(null);
        transition(order, current, OrderStatus.WAIT_DISPATCH, "重入队", null);
    }

    /**
     * 读取订单当前状态（重派前置裁决用，队列状态机口径统一收在本类）。
     *
     * @throws IllegalStateException 订单不存在
     */
    public OrderStatus statusOf(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在：" + orderId);
        }
        return OrderStatus.fromValue(order.getStatus());
    }

    /**
     * 投放回执确认：末段交接中 → 返航中（脱离返航）。
     *
     * <p><b>D-02／D-14（11-07 弥补）</b>：真实投放回执是唯一交付凭证。
     * 时间兜底把订单推到「返航中」后到达的回执<b>不得静默丢弃</b>——同样补记交付凭证，
     * 由下一拍 {@code FlightPhaseAdvancer} 凭该凭证推进「已完成」。
     *
     * <p>幂等：货物已是「已交付」时只记一条 info，不重复写货物行与 {@code task_log}。
     *
     * @throws IllegalStateException 订单不存在，或状态不属于末段交接中、返航中、已完成
     */
    @Transactional
    public void markHandoverDone(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在：" + orderId);
        }
        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (current == OrderStatus.FINISHED) {
            // 已完成：交付凭证必然已存在，重复回执幂等返回
            return;
        }
        if (current != OrderStatus.HANDOVER && current != OrderStatus.RETURNING) {
            throw new IllegalStateException("订单 " + orderId + " 当前状态 " + current.getValue()
                    + " 不允许标记交接完成");
        }
        recordDeliveryReceipt(order);
        if (current == OrderStatus.HANDOVER) {
            transition(order, OrderStatus.HANDOVER, OrderStatus.RETURNING, "投放回执-交接完成", null);
        }
        // 时间兜底先到（当前已在返航中）：只补凭证，不改订单态，等下一拍推进
    }

    /**
     * 落交付凭证：货物写「已交付」＋ {@code task_log} 留痕，二者同事务。
     *
     * <p>货物状态本身就是幂等键：重复回执发现已是「已交付」即返回，不二次写入。
     * 纯单测包内构造器不注入 {@code goodsLifecycleService} 时跳过，不覆盖货物链。
     */
    private void recordDeliveryReceipt(OrderTaskEntity order) {
        if (goodsLifecycleService == null) {
            return;
        }
        if (goodsLifecycleService.isDelivered(order.getOrderId())) {
            log.info("投放回执重复，交付凭证已存在：orderId={}", order.getOrderId());
            return;
        }
        goodsLifecycleService.deliver(order.getOrderId(), order.getDroneId());
        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(order.getOrderId());
        entry.setOpType(OP_TYPE_DELIVERY_RECEIPT);
        // from/to 记货物状态而不是订单状态：交付凭证是货物链事件，写成「返航中→返航中」
        // 会被按订单状态扫飞行阶段的查询误当成一次阶段迁移，也会让状态跳数断言失真。
        entry.setFromStatus(GoodsStatus.LOADED.getValue());
        entry.setToStatus(GoodsStatus.DELIVERED.getValue());
        entry.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), DB_ZONE));
        taskLogMapper.insert(entry);
        log.info("真实投放回执已落交付凭证：orderId={} status={}",
                order.getOrderId(), order.getStatus());
    }

    /**
     * 状态流转：更新订单状态 + 写 task_log（同事务）；落库用中文状态词。
     *
     * <p><b>11-05 序号 1（等待原因落库）</b>：{@code waitReason} 只有流入
     * 「已受理待唤醒」时才有意义，写入 {@code order_task.wait_reason} 并随
     * {@link OrderView#waitReason()} 一起对外发布；其余流转一律把它清成 NULL，
     * 避免订单离开等待态后仍回显过期原因（等船／等货／锁超时各有不同来源）。
     *
     * @param waitReason 等待原因；非「已受理待唤醒」或调用方未指定时传 null
     */
    private void transition(OrderTaskEntity order, OrderStatus from, OrderStatus to, String opType,
            WaitReason waitReason) {
        long eventTs = System.currentTimeMillis();
        WaitReason effectiveWaitReason = to == OrderStatus.ACCEPTED_WAIT_WAKE ? waitReason : null;
        String waitReasonCode = effectiveWaitReason == null ? null : effectiveWaitReason.getCode();
        // HIGH.29：状态流转走条件更新（UPDATE ... WHERE status=from），行数不为 1
        // 说明被并发推进，直接抛状态冲突，不用陈旧实体整行 updateById 覆盖
        int updated = orderTaskMapper.update(null,
                Wrappers.<OrderTaskEntity>lambdaUpdate()
                        .eq(OrderTaskEntity::getOrderId, order.getOrderId())
                        .eq(OrderTaskEntity::getStatus, from.getValue())
                        .set(OrderTaskEntity::getStatus, to.getValue())
                        .set(OrderTaskEntity::getWaitReason, waitReasonCode));
        if (updated != 1) {
            throw new IllegalStateException("订单 " + order.getOrderId()
                    + " 状态冲突：从 " + from.getValue() + " 流转到 " + to.getValue() + " 失败");
        }
        order.setStatus(to.getValue());
        order.setWaitReason(waitReasonCode);

        TaskLogEntity log = new TaskLogEntity();
        log.setOrderId(order.getOrderId());
        log.setOpType(opType);
        log.setFromStatus(from.getValue());
        log.setToStatus(to.getValue());
        log.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(eventTs), DB_ZONE));
        taskLogMapper.insert(log);

        long version = stateVersion(order.getOrderId(), log.getLogId());
        // 货巢必须带上：状态流转帧是订单视图的增量来源，不带就会把建单时选定的货巢抹成空，
        // 前端据此显示「未指定货巢」，登记货物到巢的入口也随之消失
        OrderView view = new OrderView(order.getOrderId(), to, eventTs, version,
                effectiveWaitReason, null, null, null, null, order.getCargoNestId(),
                order.getGoodsType());
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));

        // MEDIUM.54：订单进入终态/已完成后移除 fallback 键，防 map 无限增长
        if (to.isTerminal() || to == OrderStatus.FINISHED) {
            fallbackVersions.remove(order.getOrderId());
        }
    }

    private long stateVersion(String orderId, Long logId) {
        AtomicLong published = fallbackVersions.computeIfAbsent(orderId,
                ignored -> new AtomicLong(1L));
        if (logId != null) {
            // 版本 1 留给建单帧；自增日志主键保证跨进程重启后仍严格递增。
            long version = Math.max(2L, logId + 1L);
            // MEDIUM.54：记录已发布最大版本，后续 fallback 分支在其上自增、不回退
            published.accumulateAndGet(version, Math::max);
            return version;
        }
        return published.incrementAndGet();
    }

    private void requireStatus(OrderTaskEntity order, OrderStatus expected) {
        if (order == null) {
            throw new IllegalStateException("订单不存在");
        }
        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (current != expected) {
            throw new IllegalStateException(
                "订单 " + orderIdSafe(order) + " 当前状态 " + current.getValue()
                    + "，不允许从 " + expected.getValue() + " 流转");
        }
    }

    private static String orderIdSafe(OrderTaskEntity order) {
        return order == null ? "(null)" : order.getOrderId();
    }

    // --------------------------------------------------------------
    // 无人机状态回写（23 §7 状态机 + DroneEntity 三字段：status /
    // current_nest_id / position_type）。跨派发、重入队两入口。
    // FlightPhaseAdvancer 负责后续飞行阶段（起飞→执行中→返航→完成）。
    // --------------------------------------------------------------

    /**
     * 派单占位：置为「已预占」，还在原巢上。
     * 仅当 droneMapper 注入时生效（兼容单测的 mock 构造器）。
     */
    private void markDronePreoccupied(String droneId) {
        if (droneMapper == null || droneId == null) {
            return;
        }
        // HIGH.30 + MEDIUM.55：占位走条件更新，只从「空闲」转入「已预占」；
        // 行查不到或非空闲（并发派单/状态污染）直接抛异常，不静默吞掉
        int updated = droneMapper.update(null,
                Wrappers.<DroneEntity>lambdaUpdate()
                        .eq(DroneEntity::getDroneId, droneId)
                        .eq(DroneEntity::getStatus, "空闲")
                        .set(DroneEntity::getStatus, "已预占")
                        .set(DroneEntity::getPositionType, "原巢"));
        // current_nest_id 不变，仍指向原巢
        if (updated != 1) {
            throw new IllegalStateException(
                    "无人机 " + droneId + " 不存在或当前非空闲，无法占位派单");
        }
    }

    /**
     * 释放占位：置为「空闲」，current_nest_id 复位到 homeNestId。
     *
     * @param leavePosition 若为 true（重入队路径），仅改 status 与 position_type；
     *                      若为 false（FlightPhaseAdvancer 完成时调用），
     *                      current_nest_id 也复归 homeNestId
     */
    private void markDroneIdle(String droneId, boolean leavePosition) {
        if (droneMapper == null || droneId == null) {
            return;
        }
        DroneEntity drone = droneMapper.selectById(droneId);
        if (drone == null) {
            // MEDIUM.55：行查不到说明数据不一致，记警告让调用方复核，不静默吞掉
            log.warn("无人机 {} 行查不到，跳过状态回写（调用方需复核数据一致性）", droneId);
            return;
        }
        drone.setStatus("空闲");
        drone.setPositionType("原巢");
        if (!leavePosition) {
            drone.setCurrentNestId(drone.getHomeNestId());
        }
        droneMapper.updateById(drone);
    }

    /** 供 FlightPhaseAdvancer 复用的包内入口：返航/完成时调用。 */
    void markDroneFinished(String droneId) {
        markDroneIdle(droneId, false);
    }
}
