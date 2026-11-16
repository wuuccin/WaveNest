package com.waterlogistics.dispatch.reschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.HomecomingTier;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.contract.order.SafetyReview;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.alert.HomecomingInputAssembler;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
    private static final long ETA_AT = 1_764_000_600_000L;
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final List<TaskLogEntity> logs = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final List<HomecomingInput> homecomingInputs = new ArrayList<>();

    private OrderTaskEntity order;
    private OrderTaskEntity resolvedByDrone;
    private DroneEntity drone;
    private boolean homecomingThrows;
    private HomecomingTier homecomingTier = HomecomingTier.RETURN_HOME_NEST;

    /** 条件更新与按机号反查都用 lambda wrapper，单类独立跑时须先建 TableInfo 缓存。 */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, DroneEntity.class);
    }

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
        assertTrue(decisionEvents().isEmpty(), "继续档不发 InflightSafetyDecisionEvent 广播");
        assertEquals(1, orderStateEvents().size(), "继续档也随 order_state 带出复核结论");
        SafetyReview review = orderStateEvents().get(0).view().safetyReview();
        assertEquals("继续", review.verdict());
        assertEquals("在飞安全复核-继续", review.opType());
        assertTrue(review.eventTs() > 0, "eventTs 取审计行 ts 换算，不能为 0");
        assertEquals(ETA_AT, orderStateEvents().get(0).view().etaTs().longValue(),
                "复核帧须带出订单已有 etaTs，按 DB_ZONE 反解，抹空会把前端「预计送达」清空");
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
        assertEquals(1, decisionEvents().size());
        InflightSafetyDecisionEvent event = decisionEvents().get(0);
        assertEquals(InflightSafetyVerdict.Verdict.ADJUST, event.verdict());
        assertNull(event.tier(), "调整档没有落点结论");
        assertEquals(ORDER, event.orderId());
        assertTrue(homecomingInputs.isEmpty());
        assertEquals("末段交接中", order.getStatus(), "调整档不写订单状态");
        assertEquals(1, orderStateEvents().size(), "调整档随 order_state 带出复核结论");
        SafetyReview review = orderStateEvents().get(0).view().safetyReview();
        assertEquals("调整", review.verdict());
        assertEquals("在飞安全复核-调整", review.opType());
    }

    @Test
    @DisplayName("终止档：审计＋迁移两行日志、调功能 10、回写返航中并标脏周期缓存")
    void review_terminate_shouldCallHomecomingAndPublish() {
        order = order(OrderStatus.OUTBOUND);
        order.setGoodsType("应急药品");
        drone = drone();

        service().review(request(15.2, AlertLevel.CRITICAL, false));

        assertEquals(2, logs.size());
        assertEquals("在飞安全复核-终止", logs.get(0).getOpType());
        assertEquals("去程中", logs.get(0).getFromStatus());
        assertEquals("去程中", logs.get(0).getToStatus(), "审计行 from=to，不是状态迁移");
        assertEquals("在飞安全复核-中止返航", logs.get(1).getOpType());
        assertEquals("去程中", logs.get(1).getFromStatus());
        assertEquals("返航中", logs.get(1).getToStatus(),
                "迁移行在审计行之后写，logId 更大，advancer 倒序恢复才读得到返航中");
        assertEquals(1, homecomingInputs.size());
        HomecomingInput input = homecomingInputs.get(0);
        assertEquals("空域或气象", input.triggerSource());
        assertEquals("气象超限", input.abortReason());
        assertEquals("NEST-01", input.homeNestId());
        assertEquals(72.5, input.batteryPercent(), 1e-9, "电量取 drone.battery，不走低电量告警口径");
        assertTrue(input.cargoOnboard(), "订单仍在交付前，货随机返航");
        assertEquals(1, decisionEvents().size());
        InflightSafetyDecisionEvent event = decisionEvents().get(0);
        assertEquals(HomecomingTier.RETURN_HOME_NEST, event.tier());
        assertEquals("NEST-01", event.targetId());
        assertEquals("返航中", order.getStatus(), "中止返航回写（《23》§4 约束 2 例外、§8）");
        assertEquals(2, orderStateEvents().size(),
                "迁移帧（AFTER_COMMIT 先到、版本大）＋ 复核帧（后到、版本小），两帧都带复核三值");
        assertEquals(OrderStatus.RETURNING, orderStateEvents().get(0).view().orderStatus());
        assertEquals("应急药品", orderStateEvents().get(0).view().goodsType(), "气象复核回写的状态帧必须带上配送品类，否则界面把已展示的品类退回「未记录」");
        assertEquals(ETA_AT, orderStateEvents().get(0).view().etaTs().longValue(),
                "迁移帧须带出 etaTs，抹空会把前端「预计送达」清空");
        assertEquals(ETA_AT, orderStateEvents().get(1).view().etaTs().longValue(),
                "复核帧同样带出 etaTs");
        assertEquals("终止", orderStateEvents().get(0).view().safetyReview().verdict(),
                "迁移帧自身必须携带复核三值：它先到且版本更大，复核帧随后到会被严格递增判据丢弃");
        assertEquals("终止", orderStateEvents().get(1).view().safetyReview().verdict(),
                "复核帧也带同一份三值");
        assertTrue(orderStateEvents().get(0).view().stateVersion()
                >= orderStateEvents().get(1).view().stateVersion(),
                "迁移帧用迁移日志主键算版本，不小于复核帧的审计日志主键版本");
    }

    @Test
    @DisplayName("终止档迫降野外：不写返航中，订单由功能 10 置失败")
    void review_terminateFieldLanding_shouldNotWriteReturning() {
        order = order(OrderStatus.OUTBOUND);
        drone = drone();
        homecomingTier = HomecomingTier.EMERGENCY_LAND_FIELD;

        service().review(request(15.2, AlertLevel.CRITICAL, false));

        assertEquals(1, logs.size(), "只留审计，本层不写迁移行");
        assertEquals("去程中", order.getStatus(), "迫降野外的订单终态由功能 10 写，本层不覆盖");
        assertEquals(1, orderStateEvents().size(), "无迁移帧，只发复核帧");
        assertEquals("终止", orderStateEvents().get(0).view().safetyReview().verdict());
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, decisionEvents().get(0).tier());
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
        assertNull(decisionEvents().get(0).tier());
        assertEquals("去程中", order.getStatus(), "功能 10 未执行，不能凭审计说已返航");
        assertEquals(1, orderStateEvents().size(), "无迁移帧，只发复核帧");
        assertEquals("终止", orderStateEvents().get(0).view().safetyReview().verdict());
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
        assertNull(decisionEvents().get(0).tier());
        assertEquals("去程中", order.getStatus(), "电量不可得，功能 10 未执行，不回写状态");
        assertEquals(1, orderStateEvents().size(), "无迁移帧，只发复核帧");
        assertEquals("终止", orderStateEvents().get(0).view().safetyReview().verdict());
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
        assertEquals(ORDER, decisionEvents().get(0).orderId(),
                "广播用反查后的权威单号");
    }

    private List<InflightSafetyDecisionEvent> decisionEvents() {
        return events.stream()
                .filter(InflightSafetyDecisionEvent.class::isInstance)
                .map(InflightSafetyDecisionEvent.class::cast)
                .toList();
    }

    private List<OrderStateChangedEvent> orderStateEvents() {
        return events.stream()
                .filter(OrderStateChangedEvent.class::isInstance)
                .map(OrderStateChangedEvent.class::cast)
                .toList();
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private InflightSafetyService service() {
        return new InflightSafetyService(orderMapper(), taskLogMapper(), droneMapper(),
                rescheduleStub(), assembler(), eventPublisher(), null, taskQueueService());
    }

    /** 只借 stateVersion(orderId, logId)：mapper 传 null，不改行、不读库。 */
    private static TaskQueueService taskQueueService() {
        return new TaskQueueService(null, null, null);
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
        entity.setEtaTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(ETA_AT), DB_ZONE));
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
                return new HomecomingDecision(homecomingTier,
                        homecomingTier == HomecomingTier.EMERGENCY_LAND_FIELD ? null : NEST,
                        null, false, List.of(), List.of(), "电量覆盖返程，回原巢");
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
                    case "update" -> 1;
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
                        TaskLogEntity entry = (TaskLogEntity) args[0];
                        // 模拟 MyBatis-Plus 对 AUTO 主键的回填：真实库里审计行 logId < 迁移行 logId，
                        // 两帧 stateVersion 据此算出迁移帧版本更大。不回填则会双双走 null fallback，
                        // 版本顺序反转，测不出真实时序。
                        entry.setLogId((long) (logs.size() + 1));
                        logs.add(entry);
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
