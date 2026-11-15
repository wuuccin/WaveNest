package com.waterlogistics.dispatch.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskQueueService} 评审回归与货到巢关接闸单测（11-05 序号 2 · 张睿哲）。
 *
 * <ul>
 *   <li><b>MED-BUG.55 关闭钉死</b>：派发占位时无人机行查不到/非空闲
 *       （条件更新影响 0 行）必须抛异常，禁止「只告警仍继续派发」；</li>
 *   <li><b>selectNext 入参防线</b>；</li>
 *   <li><b>货到巢关接闸</b>：门关判 WAIT_CARGO 时 startDispatch 把订单留在
 *       「已受理待唤醒」且 wait_reason=WAIT_CARGO（硬判据 2 前半的单测钉）。</li>
 * </ul>
 */
class TaskQueueServiceReviewTest {

    private static final String ORDER_ID = "ORD-REV-1";
    private static final String DRONE_ID = "UAV-REV-1";

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, DroneEntity.class);
    }

    @Test
    @DisplayName("MED-BUG.55 回归：无人机行查不到/非空闲（更新 0 行）→ 抛异常，不继续派发")
    void dispatch_droneRowMissing_throws() {
        OrderTaskEntity order = order("调度中");
        OrderTaskMapper orderTaskMapper = stubOrderTaskMapper(order, 1);
        // 无人机条件更新返回 0：行缺失或已被并发占位
        DroneMapper droneMapper = stubDroneMapper(0);
        TaskQueueService service = new TaskQueueService(orderTaskMapper, stubTaskLogMapper(),
                new DispatchGateService(), droneMapper, event -> { }, null);

        assertThrows(IllegalStateException.class,
                () -> service.dispatch(ORDER_ID, DRONE_ID, LocalDateTime.now()),
                "占位更新 0 行必须抛异常（MED-BUG.55：禁止只告警仍继续派发）");
    }

    @Test
    @DisplayName("selectNext 入参：limit 越界抛 IAE；合法值放行")
    void selectNext_validatesLimit() {
        TaskQueueService service = new TaskQueueService(
                stubOrderTaskMapper(null, 1), stubTaskLogMapper(),
                new DispatchGateService(), null, event -> { }, null);

        assertThrows(IllegalArgumentException.class, () -> service.selectNext(0));
        assertThrows(IllegalArgumentException.class, () -> service.selectNext(501));
        assertEquals(List.of(), service.selectNext(10), "桩 selectList 返回空列表");
    }

    @Test
    @DisplayName("货到巢关判 WAIT_CARGO：startDispatch 转「已受理待唤醒」且 wait_reason=WAIT_CARGO")
    void startDispatch_cargoGateDefers() {
        OrderTaskEntity order = order("待调度");
        order.setCargoNestId("NEST-A");
        OrderTaskMapper orderTaskMapper = stubOrderTaskMapper(order, 1);
        CargoNestGate gate = new FixedGate(CargoNestGate.Decision.WAIT_CARGO);
        TaskQueueService service = new TaskQueueService(orderTaskMapper, stubTaskLogMapper(),
                new DispatchGateService(), null, event -> { }, gate);

        service.startDispatch(ORDER_ID);

        assertEquals("已受理待唤醒", order.getStatus(), "门关待货后订单应停在已受理待唤醒");
        assertEquals(WaitReason.WAIT_CARGO.getCode(), order.getWaitReason(),
                "wait_reason 必须为 WAIT_CARGO（硬判据 2）");
    }

    @Test
    @DisplayName("markResourceWaiting：WAIT_CARGO → WAIT_RESOURCE，释锁委托给门关")
    void markResourceWaiting_setsReasonAndReleases() {
        OrderTaskEntity order = order("已受理待唤醒");
        order.setWaitReason(WaitReason.WAIT_CARGO.getCode());
        OrderTaskMapper orderTaskMapper = stubOrderTaskMapper(order, 1);
        FixedGate gate = new FixedGate(CargoNestGate.Decision.PROCEED);
        TaskQueueService service = new TaskQueueService(orderTaskMapper, stubTaskLogMapper(),
                new DispatchGateService(), null, event -> { }, gate);

        service.markResourceWaiting(ORDER_ID);

        assertEquals(WaitReason.WAIT_RESOURCE.getCode(), order.getWaitReason());
        assertEquals(1, gate.releaseCalls, "置 WAIT_RESOURCE 前必须释放巢时隙锁");
    }

    @Test
    @DisplayName("状态流转帧带货巢：订单视图里不能把订单上的货巢抹空")
    void startDispatch_publishedViewKeepsCargoNest() {
        OrderTaskEntity order = order("待调度");
        order.setCargoNestId("NEST-A");
        OrderTaskMapper orderTaskMapper = stubOrderTaskMapper(order, 1);
        List<OrderStateChangedEvent> published = new ArrayList<>();
        TaskQueueService service = new TaskQueueService(orderTaskMapper, stubTaskLogMapper(),
                new DispatchGateService(), null,
                event -> published.add((OrderStateChangedEvent) event),
                new FixedGate(CargoNestGate.Decision.WAIT_CARGO));

        service.startDispatch(ORDER_ID);

        assertEquals(1, published.size(), "状态流转应发布一帧");
        assertEquals("NEST-A", published.get(0).view().cargoNestId(),
                "状态流转帧必须带订单上的货巢：不带就会把建单时选定的货巢抹成空，"
                        + "前端显示「未指定货巢」，登记货物到巢的入口随之消失");
    }

    // ───────────────────────── stubs ─────────────────────────

    private static OrderTaskEntity order(String status) {
        OrderTaskEntity e = new OrderTaskEntity();
        e.setOrderId(ORDER_ID);
        e.setStatus(status);
        return e;
    }

    private static OrderTaskMapper stubOrderTaskMapper(OrderTaskEntity row, int updateRows) {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return row;
                    }
                    if ("update".equals(method.getName())) {
                        return updateRows;
                    }
                    if ("selectList".equals(method.getName())) {
                        return List.of();
                    }
                    return defaultPrimitive(method.getReturnType());
                });
    }

    private static TaskLogMapper stubTaskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        return 1;
                    }
                    return defaultPrimitive(method.getReturnType());
                });
    }

    private static DroneMapper stubDroneMapper(int updateRows) {
        return (DroneMapper) Proxy.newProxyInstance(
                DroneMapper.class.getClassLoader(), new Class<?>[]{DroneMapper.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName())) {
                        return updateRows;
                    }
                    return defaultPrimitive(method.getReturnType());
                });
    }

    /** 代理桩兜底：数值型返回 0，其余 null（显式分支，避免三元表达式数值提升把 int 抬成 Long）。 */
    private static Object defaultPrimitive(Class<?> rt) {
        if (rt == int.class || rt == Integer.class) {
            return 0;
        }
        if (rt == long.class || rt == Long.class) {
            return 0L;
        }
        if (rt == boolean.class || rt == Boolean.class) {
            return false;
        }
        return null;
    }

    /** 固定结论的门关桩：evaluate 恒返回指定结论；记录 releaseLockQuietly 调用。 */
    private static class FixedGate extends CargoNestGate {
        private final Decision fixed;
        int releaseCalls;

        FixedGate(Decision fixed) {
            super(null, null, null);
            this.fixed = fixed;
        }

        @Override
        public Decision evaluate(String orderId, String cargoNestId) {
            return fixed;
        }

        @Override
        public void releaseLockQuietly(String orderId) {
            releaseCalls++;
        }
    }
}
