package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 订单级人工干预实现（M2 · 序号 3）。
 *
 * <p>一次干预 = 订单状态流转（若动作有目标态）＋ 一条 {@code task_log} 留痕 ＋
 * 进入终止态时释放迫停占位，三者同一事务；任一步失败整单回滚，不允许「只改状态不留痕」。
 *
 * <p>错误码严格按《1112-功能做实批次｜接口约定》§3.3：
 * 404 {@code 订单不存在：<orderId>}、409 {@code 订单当前状态不允许该操作：<状态中文值>}。
 * 状态词一律取 {@link OrderStatus#getValue()}，不自造状态、不写枚举名。
 *
 * <p>{@code task_log.op_type} 前缀统一为「人工干预-」，{@code from_status}／
 * {@code to_status} 取中文状态词；仅留痕的动作两者同值（与《23》§8 审计口径一致）。
 */
@Service
public class OrderInterventionServiceImpl implements OrderInterventionService {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final OccupancySlotService occupancySlotService;

    public OrderInterventionServiceImpl(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            OccupancySlotService occupancySlotService) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.occupancySlotService = occupancySlotService;
    }

    @Override
    @Transactional
    public void execute(String action, String orderId) {
        InterventionAction spec = InterventionAction.fromNormalized(action);
        if (spec == null || !spec.requiresOrderId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知干预操作：" + action + "；支持 " + InterventionAction.SUPPORTED);
        }

        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在：" + orderId);
        }

        OrderStatus current = parseStatus(order.getStatus());
        if (!spec.allows(current)) {
            throw conflict(order.getStatus());
        }

        OrderStatus target = spec.targetStatus() == null ? current : spec.targetStatus();
        String fromStatus = current.getValue();
        String toStatus = target.getValue();

        if (target != current) {
            order.setStatus(toStatus);
            order.setFailReason("人工干预：" + spec.label());
            orderTaskMapper.updateById(order);
        }

        writeAudit(orderId, spec.label(), fromStatus, toStatus);

        if (target != current && target.isTerminal()) {
            occupancySlotService.releaseByOrder(orderId);
        }
    }

    private static OrderStatus parseStatus(String value) {
        try {
            return OrderStatus.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw conflict(value);
        }
    }

    private static ResponseStatusException conflict(String statusValue) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "订单当前状态不允许该操作：" + statusValue);
    }

    private void writeAudit(String orderId, String label, String fromStatus, String toStatus) {
        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(orderId);
        entry.setOpType("人工干预-" + label);
        entry.setFromStatus(fromStatus);
        entry.setToStatus(toStatus);
        entry.setTs(LocalDateTime.ofInstant(Instant.now(), DB_ZONE));
        taskLogMapper.insert(entry);
    }
}
