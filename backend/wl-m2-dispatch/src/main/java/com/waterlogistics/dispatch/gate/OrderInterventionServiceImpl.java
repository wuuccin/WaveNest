package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.rendezvous.ManualMeetRecomputePort;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <h3>{@code adjust_meet}（序号 3 第三批）</h3>
 * 《1112》§3.2 要求「修改会面点并触发重算」，§3.1 请求体<b>不含新会面点坐标</b>，
 * 故按「按最新预测重算」执行：委托 {@link ManualMeetRecomputePort}（{@code wl-app} 实现）
 * 重解会面点并落库、推帧；本类不改订单状态，只把结论追加到审计行
 * （{@code op_type}＝「人工干预-调整会面点（结论）」）。
 *
 * <p><b>事务边界</b>：{@code adjust_meet} 的重算<b>不进</b>状态流转事务——重算内部自带
 * 事务提交会面点与帧，若被外层事务回滚会出现「帧已推、库里还是旧点」的撕裂；
 * 故状态流转改由 {@link TransactionTemplate} 显式圈定，重算在事务之外执行。
 * 重算未生效仍返回 200（动作已受理并留痕），原因走 §3.4 {@code message}，
 * 由 {@link DispatchGateService} 转成 {@code confirmed=false}。
 */
@Service
public class OrderInterventionServiceImpl implements OrderInterventionService {

    private static final Logger log = LoggerFactory.getLogger(OrderInterventionServiceImpl.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String ADJUST_MEET_REASON = "人工干预-调整会面点";

    /** 端口未装配时的回吐文案（仅无装配的独立启动／单测场景可达）。 */
    private static final String ADJUST_MEET_UNAVAILABLE = "人工调整会面点出口未装配";

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final OccupancySlotService occupancySlotService;
    private final ManualMeetRecomputePort manualMeetRecomputePort;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param orderTaskMapper         订单表
     * @param taskLogMapper           任务日志表
     * @param occupancySlotService    占位登记／释放
     * @param manualMeetRecomputePort 人工调整会面点的重算出口；容器缺该 Bean 时为 {@code null}，
     *                                保证 M2 可独立启动（与 {@code DispatchGateService} 可空口径一致）
     * @param transactionTemplate     状态流转事务模板
     */
    public OrderInterventionServiceImpl(OrderTaskMapper orderTaskMapper,
            TaskLogMapper taskLogMapper,
            OccupancySlotService occupancySlotService,
            ObjectProvider<ManualMeetRecomputePort> manualMeetRecomputePort,
            TransactionTemplate transactionTemplate) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.occupancySlotService = occupancySlotService;
        this.manualMeetRecomputePort = manualMeetRecomputePort.getIfAvailable();
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public String execute(String action, String orderId) {
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

        if (spec == InterventionAction.ADJUST_MEET) {
            return adjustMeet(order, current);
        }
        applyTransition(spec, order, current);
        return null;
    }

    /**
     * 调整会面点：按最新预测重算，不改订单状态。
     *
     * @return {@code null} 表示会面点已按最新预测改成功；非空为未生效原因
     */
    private String adjustMeet(OrderTaskEntity order, OrderStatus current) {
        String orderId = order.getOrderId();
        if (manualMeetRecomputePort == null) {
            log.warn("人工调整会面点出口未装配，只留痕：orderId={}", orderId);
            writeAudit(orderId, InterventionAction.ADJUST_MEET.label(),
                    current.getValue(), current.getValue());
            return ADJUST_MEET_UNAVAILABLE;
        }
        ManualMeetRecomputePort.Outcome outcome =
                manualMeetRecomputePort.recomputeForOrder(orderId, ADJUST_MEET_REASON);
        writeAudit(orderId,
                InterventionAction.ADJUST_MEET.label() + "（" + outcome.message() + "）",
                current.getValue(), current.getValue());
        log.info("人工调整会面点：orderId={} 结论={}", orderId, outcome);
        return outcome.committed() ? null : outcome.message();
    }

    /** 状态流转 + 留痕 + 终态释放占位，三者同一事务。 */
    private void applyTransition(InterventionAction spec, OrderTaskEntity order, OrderStatus current) {
        OrderStatus target = spec.targetStatus() == null ? current : spec.targetStatus();
        String orderId = order.getOrderId();
        transactionTemplate.executeWithoutResult(status -> {
            if (target != current) {
                order.setStatus(target.getValue());
                order.setFailReason("人工干预：" + spec.label());
                orderTaskMapper.updateById(order);
            }
            writeAudit(orderId, spec.label(), current.getValue(), target.getValue());
            if (target != current && target.isTerminal()) {
                occupancySlotService.releaseByOrder(orderId);
            }
        });
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
