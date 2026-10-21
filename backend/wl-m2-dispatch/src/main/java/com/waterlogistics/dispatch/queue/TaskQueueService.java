package com.waterlogistics.dispatch.queue;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, AtomicLong> fallbackVersions = new ConcurrentHashMap<>();

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService) {
        this(orderTaskMapper, taskLogMapper, dispatchGateService, event -> { });
    }

    @Autowired
    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            DispatchGateService dispatchGateService, ApplicationEventPublisher eventPublisher) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.dispatchGateService = dispatchGateService;
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

    /** 派发：调度中 → 已派发（第 4 步定案 + 第 5 步回填，关联无人机与起飞时刻） */
    @Transactional
    public void dispatch(String orderId, String droneId, LocalDateTime meetTime) {
        dispatch(orderId, droneId, meetTime, null, null);
    }

    /** 派发并回填 M2 已求得的组合会面方案。 */
    @Transactional
    public void dispatch(String orderId, String droneId, LocalDateTime meetTime,
            Double meetLon, Double meetLat) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.DISPATCHING);
        order.setDroneId(droneId);
        order.setMeetTime(meetTime);
        if (meetLon != null && meetLat != null) {
            order.setMeetLon(java.math.BigDecimal.valueOf(meetLon));
            order.setMeetLat(java.math.BigDecimal.valueOf(meetLat));
        }
        orderTaskMapper.updateById(order);
        transition(order, OrderStatus.DISPATCHING, OrderStatus.DISPATCHED, "派发");
        if (meetLon != null && meetLat != null) {
            eventPublisher.publishEvent(new RendezvousPlanCommittedEvent(
                    new RendezvousFrame(orderId, meetLon, meetLat, true)));
        }
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
                "订单 " + order.getOrderId() + " 当前状态 " + current.getValue()
                    + "，不允许从 " + expected.getValue() + " 流转");
        }
    }
}
