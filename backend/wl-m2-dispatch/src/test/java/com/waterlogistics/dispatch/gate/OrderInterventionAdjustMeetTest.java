package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.rendezvous.ManualMeetRecomputePort;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 人工「调整会面点」执行口径单测（M2 · 序号 3 第三批）——请求体不带坐标、
 * 按最新预测重算，结论回吐与审计留痕一致。
 *
 * <p>不起 Spring、不引 mock 框架（wl-m2 的测试依赖只有 JUnit）：订单表与日志表用动态代理替身，
 * 占位服务与事务模板传 {@code null}——{@code adjust_meet} 分支按设计不碰两者，
 * 传 {@code null} 同时验证了「调整会面点不释放占位、不进状态流转事务」。
 */
class OrderInterventionAdjustMeetTest {

    private static final String ORDER_ID = "ORD-AM-1";

    private final List<TaskLogEntity> auditRows = new ArrayList<>();
    private OrderTaskEntity orderRow;
    private boolean updateCalled;
    private ManualMeetRecomputePort.Outcome outcome;
    private String lastReason;
    private String lastPortOrderId;

    @BeforeEach
    void setUp() {
        auditRows.clear();
        updateCalled = false;
        lastReason = null;
        lastPortOrderId = null;
        outcome = ManualMeetRecomputePort.Outcome.COMMITTED;
        orderRow = order(OrderStatus.OUTBOUND.getValue());
    }

    @Test
    @DisplayName("adjust_meet 成功 → 返回 null、回调口径正确、不改订单状态、审计行带结论")
    void adjustMeetCommitted_shouldReturnNullAndAuditOutcome() {
        String rejection = service(port()).execute("adjust_meet", ORDER_ID);

        assertNull(rejection, "会面点已重算成功时 message 应为空串");
        assertEquals(ORDER_ID, lastPortOrderId);
        assertEquals("人工干预-调整会面点", lastReason, "触发原因应写明人工来源，不得伪造 6a 信号");
        assertFalse(updateCalled, "调整会面点不改订单状态");
        assertEquals(1, auditRows.size());
        TaskLogEntity row = auditRows.get(0);
        assertEquals("人工干预-调整会面点（会面点已按最新预测重算）", row.getOpType());
        assertEquals(OrderStatus.OUTBOUND.getValue(), row.getFromStatus());
        assertEquals(OrderStatus.OUTBOUND.getValue(), row.getToStatus(), "仅留痕动作前后状态同值");
    }

    @Test
    @DisplayName("无解 → 回吐原因、仍留痕一行、订单状态不变")
    void noSolution_shouldReturnReasonAndKeepStatus() {
        outcome = ManualMeetRecomputePort.Outcome.NO_SOLUTION;

        String rejection = service(port()).execute("adjust_meet", ORDER_ID);

        assertEquals("按最新预测求无解，保留原会面点", rejection);
        assertFalse(updateCalled);
        assertEquals(1, auditRows.size());
        assertTrue(auditRows.get(0).getOpType().contains("按最新预测求无解"),
                "审计行要能看出这次调整没生效");
    }

    @Test
    @DisplayName("出口未装配 → 不抛异常，回吐未装配原因并留痕")
    void portMissing_shouldReportInsteadOfThrow() {
        String rejection = service(null).execute("adjust_meet", ORDER_ID);

        assertEquals("人工调整会面点出口未装配", rejection);
        assertFalse(updateCalled);
        assertEquals(1, auditRows.size());
        assertEquals("人工干预-调整会面点", auditRows.get(0).getOpType());
    }

    private OrderInterventionServiceImpl service(ManualMeetRecomputePort port) {
        return new OrderInterventionServiceImpl(orderTaskMapper(), taskLogMapper(), null,
                provider(port), null);
    }

    private ManualMeetRecomputePort port() {
        return (orderId, reason) -> {
            lastPortOrderId = orderId;
            lastReason = reason;
            return outcome;
        };
    }

    private static OrderTaskEntity order(String status) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(ORDER_ID);
        order.setStatus(status);
        order.setDroneId("UAV-01");
        return order;
    }

    private OrderTaskMapper orderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return orderRow;
                    }
                    if ("updateById".equals(method.getName())) {
                        updateCalled = true;
                        return 1;
                    }
                    return null;
                });
    }

    private TaskLogMapper taskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        auditRows.add((TaskLogEntity) args[0]);
                        return 1;
                    }
                    return null;
                });
    }

    private static ObjectProvider<ManualMeetRecomputePort> provider(ManualMeetRecomputePort port) {
        return new ObjectProvider<>() {
            @Override
            public ManualMeetRecomputePort getObject() {
                return port;
            }

            @Override
            public ManualMeetRecomputePort getObject(Object... args) {
                return port;
            }

            @Override
            public ManualMeetRecomputePort getIfAvailable() {
                return port;
            }

            @Override
            public ManualMeetRecomputePort getIfUnique() {
                return port;
            }
        };
    }
}
