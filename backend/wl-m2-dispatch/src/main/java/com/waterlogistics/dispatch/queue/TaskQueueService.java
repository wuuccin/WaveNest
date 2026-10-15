package com.waterlogistics.dispatch.queue;

import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务队列与状态管理（模块二 功能 8，算法见 83 §6）。
 * 本类只负责订单状态机流转（枚举照 23 §3，写入方照 23 §8），不做业务判断。
 *
 * 状态词一律用 23 §3 的中文写法（23 §9 禁用表）；后续接 wl-contract.state.OrderStatus 枚举。
 * 演示阶段（10-15）只实现主链：待受理 → 待调度 → 调度中 → 已派发。
 */
@Service
public class TaskQueueService {

    // 订单状态（23 §3，12 态）
    public static final String S_PENDING_ACCEPT = "待受理";
    public static final String S_AWAITING_WAKE = "已受理待唤醒";
    public static final String S_READY_DISPATCH = "待调度";
    public static final String S_DISPATCHING = "调度中";
    public static final String S_DISPATCHED = "已派发";
    public static final String S_OUTBOUND = "去程中";
    public static final String S_HANDOVER = "末段交接中";
    public static final String S_RETURNING = "返航中";
    public static final String S_DONE = "已完成";
    public static final String S_REJECTED = "已拒绝";
    public static final String S_FAILED = "失败";
    public static final String S_ABORTED = "异常终止";

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
    }

    /**
     * 受理：待受理 → 待调度（第 1 步受理判定，演示阶段默认满足派单前置门直接可派）。
     * 等船/等货分支（→ 已受理待唤醒 + wakeReason）16 号接入，此处留 TODO。
     */
    @Transactional
    public void accept(OrderTaskEntity order) {
        requireStatus(order, S_PENDING_ACCEPT);
        transition(order, S_PENDING_ACCEPT, S_READY_DISPATCH, "受理");
    }

    /** 组批调度：待调度 → 调度中（第 2 步起开始求解） */
    @Transactional
    public void startDispatch(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, S_READY_DISPATCH);
        transition(order, S_READY_DISPATCH, S_DISPATCHING, "组批调度");
    }

    /** 派发：调度中 → 已派发（第 4 步定案 + 第 5 步回填，关联无人机与起飞时刻） */
    @Transactional
    public void dispatch(String orderId, String droneId, LocalDateTime meetTime) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, S_DISPATCHING);
        order.setDroneId(droneId);
        order.setMeetTime(meetTime);
        orderTaskMapper.updateById(order);
        transition(order, S_DISPATCHING, S_DISPATCHED, "派发");
    }

    /** 状态流转：更新订单状态 + 写 task_log（同事务） */
    private void transition(OrderTaskEntity order, String from, String to, String opType) {
        order.setStatus(to);
        orderTaskMapper.updateById(order);

        TaskLogEntity log = new TaskLogEntity();
        log.setOrderId(order.getOrderId());
        log.setOpType(opType);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setTs(LocalDateTime.now());
        taskLogMapper.insert(log);
    }

    private void requireStatus(OrderTaskEntity order, String expected) {
        if (order == null) {
            throw new IllegalStateException("订单不存在");
        }
        if (!expected.equals(order.getStatus())) {
            throw new IllegalStateException(
                "订单 " + order.getOrderId() + " 当前状态 " + order.getStatus()
                    + "，不允许从 " + expected + " 流转");
        }
    }
}
