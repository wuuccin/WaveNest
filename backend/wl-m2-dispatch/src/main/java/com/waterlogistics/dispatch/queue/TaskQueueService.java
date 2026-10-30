package com.waterlogistics.dispatch.queue;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
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

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final DispatchGateService dispatchGateService;
    private final DroneMapper droneMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, AtomicLong> fallbackVersions = new ConcurrentHashMap<>();

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, null, event -> { });
    }

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, ApplicationEventPublisher eventPublisher) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, null, eventPublisher);
    }

    @Autowired
    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, DroneMapper droneMapper,
            ApplicationEventPublisher eventPublisher) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.dispatchGateService = dispatchGateService;
        this.droneMapper = droneMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 受理：待受理 → 待调度（第 1 步受理判定，演示阶段默认满足派单前置门直接可派）。
     * 等船/等货分支（→ 已受理待唤醒 + 算法层 wakeReason 四类，M5 投影 WaitReason
     * 三值）16 号接入，此处留 TODO。
     */
    @Transactional
    public void accept(OrderTaskEntity order) {
        requireStatus(order, OrderStatus.PENDING_ACCEPT);
        transition(order, OrderStatus.PENDING_ACCEPT, OrderStatus.WAIT_DISPATCH, "受理");
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
                    "闸门熔断-延后");
            return;
        }

        transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.DISPATCHING, "组批调度");
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
        order.setDroneId(droneId);
        order.setMeetTime(meetTime);
        orderTaskMapper.updateById(order);
        markDronePreoccupied(droneId);
        transition(order, OrderStatus.DISPATCHING, OrderStatus.DISPATCHED, "派发");
    }

    /**
     * 重入队（10-16 闭环第 4 环）：放弃会面后订单回到待调度，清空派单字段
     * （droneId/meetTime/meetLon/meetLat），原机不再关联。
     * 仅允许非终止态回退；终止态（已拒绝/失败/异常终止）不允许重入队。
     */
    @Transactional
    public void requeue(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在：" + orderId);
        }
        OrderStatus current = OrderStatus.fromValue(order.getStatus());
        if (current.isTerminal()) {
            throw new IllegalStateException(
                "订单 " + orderId + " 已终止（" + current.getValue() + "），不允许重入队");
        }
        // 放弃会面 → 执行机释放，状态回空闲（position_type 复位、current_nest_id 不变）
        if (order.getDroneId() != null) {
            markDroneIdle(order.getDroneId(), true);
        }
        order.setDroneId(null);
        order.setMeetTime(null);
        order.setMeetLon(null);
        order.setMeetLat(null);
        transition(order, current, OrderStatus.WAIT_DISPATCH, "重入队");
    }

    /** 状态流转：更新订单状态 + 写 task_log（同事务）；落库用中文状态词 */
    private void transition(OrderTaskEntity order, OrderStatus from, OrderStatus to, String opType) {
        long eventTs = System.currentTimeMillis();
        order.setStatus(to.getValue());
        orderTaskMapper.updateById(order);

        TaskLogEntity log = new TaskLogEntity();
        log.setOrderId(order.getOrderId());
        log.setOpType(opType);
        log.setFromStatus(from.getValue());
        log.setToStatus(to.getValue());
        log.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(eventTs), DB_ZONE));
        taskLogMapper.insert(log);

        long version = stateVersion(order.getOrderId(), log.getLogId());
        WaitReason waitReason = to == OrderStatus.ACCEPTED_WAIT_WAKE
                ? WaitReason.GATE_OR_DATA : null;
        OrderView view = new OrderView(order.getOrderId(), to, eventTs, version,
                waitReason, null, null, null);
        eventPublisher.publishEvent(new OrderStateChangedEvent(view));
    }

    private long stateVersion(String orderId, Long logId) {
        if (logId != null) {
            // 版本 1 留给建单帧；自增日志主键保证跨进程重启后仍严格递增。
            return Math.max(2L, logId + 1L);
        }
        return fallbackVersions.computeIfAbsent(orderId, ignored -> new AtomicLong(1L))
                .incrementAndGet();
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
        DroneEntity drone = droneMapper.selectById(droneId);
        if (drone == null) {
            return;
        }
        drone.setStatus("已预占");
        drone.setPositionType("原巢");
        // current_nest_id 不变，仍指向原巢
        droneMapper.updateById(drone);
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
