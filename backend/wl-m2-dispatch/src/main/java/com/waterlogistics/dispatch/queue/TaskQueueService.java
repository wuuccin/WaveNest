package com.waterlogistics.dispatch.queue;

import com.waterlogistics.contract.state.OrderStatus;
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
 * 状态一律用 wl-contract.state.OrderStatus（落库取 getValue() 中文状态词，
 * 与 order_task.status 列注释一致）；23 §9 禁用词由契约枚举天然规避。
 * 演示阶段（10-15）只实现主链：待受理 → 待调度 → 调度中 → 已派发。
 */
@Service
public class TaskQueueService {

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;

    public TaskQueueService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
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

    /** 组批调度：待调度 → 调度中（第 2 步起开始求解） */
    @Transactional
    public void startDispatch(String orderId) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.WAIT_DISPATCH);
        transition(order, OrderStatus.WAIT_DISPATCH, OrderStatus.DISPATCHING, "组批调度");
    }

    /** 派发：调度中 → 已派发（第 4 步定案 + 第 5 步回填，关联无人机与起飞时刻） */
    @Transactional
    public void dispatch(String orderId, String droneId, LocalDateTime meetTime) {
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        requireStatus(order, OrderStatus.DISPATCHING);
        order.setDroneId(droneId);
        order.setMeetTime(meetTime);
        orderTaskMapper.updateById(order);
        transition(order, OrderStatus.DISPATCHING, OrderStatus.DISPATCHED, "派发");
    }

    /** 状态流转：更新订单状态 + 写 task_log（同事务）；落库用中文状态词 */
    private void transition(OrderTaskEntity order, OrderStatus from, OrderStatus to, String opType) {
        order.setStatus(to.getValue());
        orderTaskMapper.updateById(order);

        TaskLogEntity log = new TaskLogEntity();
        log.setOrderId(order.getOrderId());
        log.setOpType(opType);
        log.setFromStatus(from.getValue());
        log.setToStatus(to.getValue());
        log.setTs(LocalDateTime.now());
        taskLogMapper.insert(log);
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
