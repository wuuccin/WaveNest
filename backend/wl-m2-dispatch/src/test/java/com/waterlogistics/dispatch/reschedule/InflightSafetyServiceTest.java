package com.waterlogistics.dispatch.reschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.HomecomingInputAssembler;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 在飞安全复核编排测试（序号 2-2）。
 *
 * <p>无库、无容器：三个 Mapper 用 JDK 动态代理，{@code RescheduleService} 用匿名子类覆写
 * 功能 10 的入口，事件发布器用代理收集。覆盖契约 §3.5 六类失败、三条判档与广播口径。
 *
 * <p><b>事务不在这里验</b>：{@code TransactionTemplate} 传 {@code null}，退化为直接执行；
 * 回滚语义（功能 10 抛错则审计一并撤掉）由生产事务模板保证，单测只断言异常向上抛。
 */
class InflightSafetyServiceTest {

    private static final String ORDER = "ORD-1";
    private static final String DRONE = "UAV-01";
    private static final String NEST = "NEST-01";
    private static final long OBSERVED_AT = 1_764_000_000_000L;

    private final List<TaskLogEntity> logs = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final List<HomecomingInput> homecomingInputs = new ArrayList<>();

    private OrderTaskEntity order;
    private OrderTaskEntity resolvedByDrone;
    private DroneEntity drone;
    private boolean homecomingThrows;

    // ───────────────────────── 三条判档 ─────────────────────────

    @Test
    @DisplayName("继续档：留痕一条，不广播、不调功能 10")
    void review_continue_shouldAuditOnly() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();

        Optional<InflightSafetyVerdict> verdict = service().review(request(9.0, AlertLevel.WARN, true));

        assertTrue(verdict.isPresent());
        assertEquals(InflightSafetyVerdict.Verdict.CONTINUE, verdict.get().verdict());
        assertEquals(1, logs.size());
        assertEquals("在飞安全复核-继续", logs.get(0).getOpType());
        assertEquals("去程中", logs.get(0).getFromStatus());
        assertEquals("去程中", logs.get(0).getToStatus(), "审计不是状态迁移，from 与 to 同为当前状态");
        assertTrue(events.isEmpty(), "继续档不发广播");
        assertTrue(homecomingInputs.isEmpty(), "继续档不进功能 10");
    }

    @Test
    @DisplayName("调整档：留痕并广播，订单状态不动、不调功能 10")
    void review_adjust_shouldAuditAndPublish() {
        order = order(OrderStatus.HANDOVER);
        drone = drone();

        service().review(request(9.5, AlertLevel.WARN, false));

        assertEquals(1, logs.size());
        assertEquals("在飞安全复核-调整", logs.get(0).getOpType());
        assertEquals("末段交接中", logs.get(0).getToStatus());
        assertEquals(1, events.size());
        InflightSafetyDecisionEvent event = (InflightSafetyDecisionEvent) events.get(0);
        assertEquals(InflightSafetyVerdict.Verdict.ADJUST, event.verdict());
        assertNull(event.tier(), "调整档没有落点结论");
        assertEquals(ORDER, event.orderId());
        assertTrue(homecomingInputs.isEmpty());
        assertEquals("末段交接中", order.getStatus(), "本类不写订单状态");
    }

    @Test
    @DisplayName("终止档：留痕、调功能 10 一次、广播带落点，仍不改订单状态")
    void review_terminate_shouldCallHomecomingAndPublish() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();

        service().review(request(15.2, AlertLevel.CRITICAL, false));

        assertEquals(1, logs.size());
        assertEquals("在飞安全复核-终止", logs.get(0).getOpType());
        assertEquals(1, homecomingInputs.size());
        HomecomingInput input = homecomingInputs.get(0);
        assertEquals("空域或气象", input.triggerSource());
        assertEquals("气象超限", input.abortReason());
        assertEquals("NEST-01", input.homeNestId());
        assertEquals(72.5, input.batteryPercent(), 1e-9, "电量取 drone.battery，不走低电量告警口径");
        assertTrue(input.cargoOnboard(), "订单仍在交付前，货随机返航");
        assertEquals(1, events.size());
        InflightSafetyDecisionEvent event = (InflightSafetyDecisionEvent) events.get(0);
        assertEquals(HomecomingTier.RETURN_HOME_NEST, event.tier());
        assertEquals("NEST-01", event.targetId());
        assertEquals("去程中", order.getStatus(), "终止档不写订单状态（《23》§3 约束 2）");
    }

    // ───────────────────────── 六类失败 ─────────────────────────

    @Test
    @DisplayName("订单查不到：空结论，零留痕、零广播")
    void review_orderMissing_shouldReturnEmpty() {
        order = null;

        assertTrue(service().review(request(15.2, AlertLevel.CRITICAL, false)).isEmpty());
        assertTrue(logs.isEmpty());
        assertTrue(events.isEmpty());
        assertTrue(homecomingInputs.isEmpty());
    }

    @Test
    @DisplayName("订单在返航中：空结论，不判档")
    void review_orderReturning_shouldReturnEmpty() {
        order = order(OrderStatus.RETURNING);
        drone = drone();

        assertTrue(service().review(request(15.2, AlertLevel.CRITICAL, false)).isEmpty());
        assertTrue(logs.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("风速非法：空结论，不兜成继续")
    void review_invalidWind_shouldReturnEmpty() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();

        assertTrue(service().review(request(Double.NaN, AlertLevel.WARN, false)).isEmpty());
        assertTrue(service().review(request(-1.0, AlertLevel.WARN, false)).isEmpty());
        assertTrue(logs.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("级别不合法（提示级）：空结论")
    void review_invalidLevel_shouldReturnEmpty() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();

        assertTrue(service().review(request(9.0, AlertLevel.INFO, false)).isEmpty());
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("功能 10 抛异常：异常向上抛，由事务模板整体回滚")
    void review_homecomingThrows_shouldPropagate() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();
        homecomingThrows = true;

        assertThrows(IllegalStateException.class,
                () -> service().review(request(15.2, AlertLevel.CRITICAL, false)));
        assertTrue(events.isEmpty(), "事务未成功，不广播");
    }

    @Test
    @DisplayName("终止档缺会面点：仍留痕与广播，但不臆造返程起点")
    void review_terminateWithoutMeetPoint_shouldAuditWithoutHomecoming() {
        order = order(OrderStatus.OUTBOUND);
        order.setMeetLon(null);
        order.setMeetLat(null);
        drone = drone();

        service().review(request(15.2, AlertLevel.CRITICAL, false));

        assertEquals(1, logs.size());
        assertTrue(homecomingInputs.isEmpty(), "无会面点则不执行功能 10");
        assertEquals(1, events.size());
        assertNull(((InflightSafetyDecisionEvent) events.get(0)).tier());
    }

    @Test
    @DisplayName("终止档电量不可得：仍留痕与广播，不伪造电量")
    void review_terminateWithoutBattery_shouldAuditWithoutHomecoming() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();
        drone.setBattery(null);

        service().review(request(15.2, AlertLevel.CRITICAL, false));

        assertEquals(1, logs.size());
        assertTrue(homecomingInputs.isEmpty());
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("告警未带单号：按机号反查在飞订单后正常复核")
    void review_blankOrderId_shouldResolveByDrone() {
        order = null;
        resolvedByDrone = order(OrderStatus.OUTBOUND);
        drone = drone();

        Optional<InflightSafetyVerdict> verdict = service().review(new InflightSafetyRequest(
                "EVT-2", InflightSafetyTrigger.AIRSPACE_OR_WEATHER, null, DRONE,
                9.5, AlertLevel.WARN, 30, OBSERVED_AT, false));

        assertTrue(verdict.isPresent());
        assertEquals(InflightSafetyVerdict.Verdict.ADJUST, verdict.get().verdict());
        assertEquals(ORDER, ((InflightSafetyDecisionEvent) events.get(0)).orderId(),
                "广播用反查后的权威单号");
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private InflightSafetyService service() {
        return new InflightSafetyService(orderMapper(), taskLogMapper(), droneMapper(),
                rescheduleStub(), assembler(), eventPublisher(), null);
    }

    private static InflightSafetyRequest request(double wind, AlertLevel level, boolean cleared) {
        return new InflightSafetyRequest("EVT-1", InflightSafetyTrigger.AIRSPACE_OR_WEATHER,
                ORDER, DRONE, wind, level, 30, OBSERVED_AT, cleared);
    }

    private static OrderTaskEntity order(OrderStatus status) {
        OrderTaskEntity entity = new OrderTaskEntity();
        entity.setOrderId(ORDER);
        entity.setDroneId(DRONE);
        entity.setStatus(status.getValue());
        entity.setMeetLon(new BigDecimal("115.8600"));
        entity.setMeetLat(new BigDecimal("28.6700"));
        return entity;
    }

    private static DroneEntity drone() {
        DroneEntity entity = new DroneEntity();
        entity.setDroneId(DRONE);
        entity.setHomeNestId(NEST);
        entity.setBattery(new BigDecimal("72.5"));
        entity.setStatus("执行任务中");
        return entity;
    }

    /** 只借 {@code params()}，不碰气象仓；{@code SysParamLoader} 传 null 时缓存为空，全部走默认值。 */
    private static HomecomingInputAssembler assembler() {
        return new HomecomingInputAssembler(new SysParamLoader(null), null);
    }

    private RescheduleService rescheduleStub() {
        return new RescheduleService(null, null, null) {
            @Override
            public HomecomingDecision abandonMeetAndReturnHome(HomecomingInput input,
                    HomecomingParams params, List<NestCandidate> candidates) {
                homecomingInputs.add(input);
                if (homecomingThrows) {
                    throw new IllegalStateException("功能 10 判定失败（单测模拟）");
                }
                return new HomecomingDecision(HomecomingTier.RETURN_HOME_NEST, NEST, null,
                        false, List.of(), List.of(), "电量覆盖返程，回原巢");
            }
        };
    }

    private OrderTaskMapper orderMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(),
                new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> order;
                    case "selectOne" -> resolvedByDrone;
                    case "toString" -> "stubOrderTaskMapper";
                    default -> null;
                });
    }

    private TaskLogMapper taskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(),
                new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        logs.add((TaskLogEntity) args[0]);
                        yield 1;
                    }
                    case "toString" -> "stubTaskLogMapper";
                    default -> null;
                });
    }

    private DroneMapper droneMapper() {
        return (DroneMapper) Proxy.newProxyInstance(
                DroneMapper.class.getClassLoader(),
                new Class<?>[]{DroneMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> drone;
                    case "toString" -> "stubDroneMapper";
                    default -> null;
                });
    }

    private ApplicationEventPublisher eventPublisher() {
        return (ApplicationEventPublisher) Proxy.newProxyInstance(
                ApplicationEventPublisher.class.getClassLoader(),
                new Class<?>[]{ApplicationEventPublisher.class},
                (proxy, method, args) -> {
                    if ("publishEvent".equals(method.getName())) {
                        events.add(args[0]);
                    }
                    return null;
                });
    }
}
