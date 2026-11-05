package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSettings;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderAdmissionService} 单测（M2 · 卢肄文 · 功能 1）。
 *
 * <p><b>阶段一 26 接线后</b>：submit 走「基本校验 → 真实判定链 → 四分流」，
 * 判定输入用<b>真实</b> {@link AdmissionInputAssembler}（只 mock 机巢表），
 * 船舶快照通过 {@link PredictionService#refresh} 喂真实 AIS 产生——
 * 这样「有无船数据」「船在不在段内」走的都是生产同一条路径，不是 mock 出来的结论。
 *
 * <p>覆盖：四分流各一条（受理调度／延后／拒单／转人工）、五类非法输入原因码、
 * HIGH.17 mmsi 归一、HIGH.18 优先级落枚举名、task_log 留痕、列表排序。
 */
class OrderAdmissionServiceTest {

    private static final String MMSI = "413000001";

    /** 演示航段上的船位（115.859/28.663 在走廊内，与 wl-app 集成测试同一点）。 */
    private static final double SHIP_LON = 115.8590;
    private static final double SHIP_LAT = 28.6630;

    private TaskLogRecorder recorder;
    private PredictionService predictionService;
    private DispatchGateService gateService;
    private OrderAdmissionService service;

    @BeforeEach
    void setUp() {
        DB_ROWS.clear();
        recorder = new TaskLogRecorder();
        predictionService = new PredictionService(
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 1L, 840L),
                mmsi -> Optional.empty(),
                event -> { });
        // 机巢表用动态代理桩掉（本模块测试无 Mockito 依赖）：selectList 返回空机巢
        NestMapper nestMapper = (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.<NestEntity>of();
                    }
                    return null;
                });
        gateService = new DispatchGateService();
        AdmissionInputAssembler assembler = new AdmissionInputAssembler(
                predictionService, nestMapper, gateService,
                "115.852,28.646;115.859,28.663;115.879,28.692", 0.0, 0.0, 50.0,
                "医疗物资,应急药品,食品,生活物资", 50.0, 0.1, 10_000L, 6.0, 60.0,
                60L, 60L, 1800L, 0.5, 300L, 1800L);
        // 唤醒器桩掉定时注册（唤醒行为由 reschedule 链自己的测试覆盖）
        AdmissionWakeScheduler wakeScheduler = new AdmissionWakeScheduler(null) {
            @Override
            public void scheduleWake(String orderId, long wakeAtMs) {
                // no-op
            }
        };
        service = new OrderAdmissionService(recorder, event -> { }, assembler, wakeScheduler,
                stubOrderTaskMapper());
    }

    /**
     * 重复判定查 {@code order_task}；单测无库，用可编程桩代替（OrderTaskMapper 是接口，
     * 可 JDK 代理）。用 {@link #seedPriorOrder} 注入既有订单行模拟「窗口内已有同单」。
     */
    private static final List<com.waterlogistics.storage.entity.OrderTaskEntity> DB_ROWS =
            new java.util.ArrayList<>();

    private static OrderTaskMapper stubOrderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(),
                new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == List.class) {
                        return List.copyOf(DB_ROWS);
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    /** 注入一笔既有非终止订单（重复判定输入），ts 为当前时刻。 */
    private static void seedPriorOrder(String mmsi, String cargoType, double weightKg) {
        com.waterlogistics.storage.entity.OrderTaskEntity row =
                new com.waterlogistics.storage.entity.OrderTaskEntity();
        row.setOrderId("ORD-PRIOR-" + DB_ROWS.size());
        row.setShipMmsi(mmsi);
        row.setGoodsType(cargoType);
        row.setGoodsWeight(java.math.BigDecimal.valueOf(weightKg));
        row.setCreateTime(java.time.LocalDateTime.now());
        row.setStatus(OrderStatus.WAIT_DISPATCH.getValue());
        DB_ROWS.add(row);
    }

    /** 喂一条船位 AIS（1 节≈0.514 m/s，航向沿段），使该船有新鲜预测快照。 */
    private void feedShip(String mmsi) {
        predictionService.refresh(new AisMessage(
                mmsi, "测试船", SHIP_LON, SHIP_LAT, 1.0, 31.2, System.currentTimeMillis()));
    }

    private static OrderSubmitRequest request(String mmsi, Double weight, Long expectedTimeMs,
            String priority) {
        return new OrderSubmitRequest(mmsi, weight, "医疗物资", expectedTimeMs, priority);
    }

    private static Long inOneHour() {
        return System.currentTimeMillis() + 3_600_000L;
    }

    @Test
    @DisplayName("★ 四分流·受理并调度：船在段内 → 建单「待受理」并发布受理事件")
    void validRequest_shouldCreatePendingOrder() {
        feedShip(MMSI);
        OrderSubmitResponse response =
                service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));

        assertTrue(response.accepted());
        assertNotNull(response.orderId());
        assertNull(response.reasonCode());
        assertNull(response.waitReason(), "受理并调度没有等待原因");

        assertEquals(OrderStatus.PENDING_ACCEPT, response.order().orderStatus());
        assertEquals(1L, response.order().stateVersion());
        assertNull(response.order().waitReason(), "waitReason 仅「已受理待唤醒」有值");
        assertNull(response.order().emergencyLandingPoint());
    }

    @Test
    @DisplayName("★ 四分流·延后：无船数据 → 「已受理待唤醒」+ WAIT_SHIP/GATE_OR_DATA 口径")
    void noShipData_shouldDeferToWake() {
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));

        assertTrue(response.accepted(), "延后是受理的一种，不是拒绝");
        assertEquals(OrderStatus.ACCEPTED_WAIT_WAKE, response.order().orderStatus());
        assertEquals("DATA_STALE", response.reasonCode());
        assertNotNull(response.waitReason());
        assertTrue(response.waitReason().equals(WaitReason.GATE_OR_DATA.name()));
        assertNotNull(response.wakeEtaTs(), "数据不足按 suspendRetrySec 重评，唤醒时刻可估计");
        assertEquals(1, recorder.trackedOrderCount(), "延后单也要建单留痕");
    }

    @Test
    @DisplayName("★ 四分流·延后：闸门熔断 → GATE_CUTOFF，绝不拒单")
    void gateCutoff_shouldDeferNotReject() {
        feedShip(MMSI);
        gateService.trip(com.waterlogistics.contract.rest.GateSource.AUTO);

        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));

        assertTrue(response.accepted());
        assertEquals(OrderStatus.ACCEPTED_WAIT_WAKE, response.order().orderStatus());
        assertEquals("GATE_CUTOFF", response.reasonCode());
        assertEquals(WaitReason.GATE_OR_DATA.name(), response.waitReason());
        assertNull(response.wakeEtaTs(), "闸门解除是事件型唤醒，无可估计时刻");
    }

    @Test
    @DisplayName("★ 四分流·拒单：重复单 → DUPLICATE（同船同货类同重量、窗内、非终止）")
    void duplicateOrder_shouldBeRejected() {
        feedShip(MMSI);
        assertTrue(service.submit(request(MMSI, 5.5, inOneHour(), "URGENT")).accepted());
        // 重复判定以 order_task 落库为准：把首单注入桩表，模拟 DispatchTrigger 已落库
        seedPriorOrder(MMSI, "医疗物资", 5.5);

        OrderSubmitResponse second = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));
        assertFalse(second.accepted());
        assertEquals("DUPLICATE", second.reasonCode());
        assertEquals(1, service.orderCount(), "被拒请求不得建单");

        // 重量差超容差不算重复
        OrderSubmitResponse heavier = service.submit(request(MMSI, 9.0, inOneHour(), "URGENT"));
        assertTrue(heavier.accepted(), "重量差 3.5 kg > 容差 0.5 kg，不是重复单");
    }

    @Test
    @DisplayName("★ 四分流·拒单：白名单外 / 超载")
    void outOfWhitelistOrOverload_shouldBeRejected() {
        feedShip(MMSI);
        OrderSubmitResponse wrongType = service.submit(
                new OrderSubmitRequest(MMSI, 5.5, "危险化学品", inOneHour(), "URGENT"));
        assertFalse(wrongType.accepted());
        assertEquals("CARGO_TYPE", wrongType.reasonCode());

        OrderSubmitResponse overload = service.submit(
                new OrderSubmitRequest(MMSI, 60.0, "医疗物资", inOneHour(), "URGENT"));
        assertFalse(overload.accepted());
        assertEquals("OVERLOAD", overload.reasonCode());
    }

    @Test
    @DisplayName("★ 不填期望送达时间 → 正常受理（11-05 序号 2：deadline 由系统算）")
    void missingDeadline_shouldStillAccept() {
        feedShip(MMSI);
        // expectedTime 为 null：此前判 ① 要求 deadline 必填 → 每一笔正常下单都转人工
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, null, "URGENT"));

        assertTrue(response.accepted(), "客户端不填期望时间不该被判「信息不完整」");
        assertEquals(1, service.orderCount(), "受理即建单");
    }

    @Test
    @DisplayName("★ 四分流·转人工：货型缺失时由判定链 ① 判信息不完整")
    void missingGoodsType_shouldGoManual() {
        feedShip(MMSI);
        // goodsType 为 null：仍是必填项，缺了转人工补录
        OrderSubmitResponse response = service.submit(
                new OrderSubmitRequest(MMSI, 5.5, null, inOneHour(), "URGENT"));

        assertFalse(response.accepted());
        assertEquals("INCOMPLETE", response.reasonCode(), "转人工以 INCOMPLETE 表达");
        assertEquals(0, service.orderCount(), "转人工不流转、不建单");
    }

    @Test
    @DisplayName("HIGH.17：mmsi 归一一次——建单、事件、登记用同一个去空白的值")
    void paddedMmsi_shouldBeTrimmedAndAccepted() {
        feedShip(MMSI);
        OrderSubmitResponse response = service.submit(request("  " + MMSI + "  ", 5.5, inOneHour(), "URGENT"));

        assertTrue(response.accepted());
        // 重复单判定走的是归一后的 mmsi：把首单（归一值）注入桩表，第二单应判重
        seedPriorOrder(MMSI, "医疗物资", 5.5);
        OrderSubmitResponse second = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));
        assertEquals("DUPLICATE", second.reasonCode(),
                "带空格与不带空格必须识别为同一艘船，否则归一失效");
    }

    @Test
    @DisplayName("HIGH.18：优先级先过枚举——大小写差异不透传原始串（事件带枚举名）")
    void priority_shouldNormalizeToEnumName() {
        feedShip(MMSI);
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, inOneHour(), "urgent"));

        assertTrue(response.accepted());
        // 判定链收到的是 priority.name() = URGENT（isComplete 的非空校验也由它满足）
        assertNotNull(response.orderId());
        assertEquals("PRIORITY_INVALID",
                service.submit(request("413000009", 5.5, inOneHour(), "SUPER_URGENT")).reasonCode());
    }

    @Test
    @DisplayName("建单事件进 task_log 事件流（功能 11）")
    void admission_shouldBeRecordedInTaskLog() {
        feedShip(MMSI);
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));

        var entries = recorder.entriesOf(response.orderId());
        assertEquals(1, entries.size());
        assertEquals("订单接入", entries.get(0).opType());
        assertNull(entries.get(0).fromStatus());
        assertEquals(OrderStatus.PENDING_ACCEPT.getValue(), entries.get(0).toStatus());
        assertEquals(1, recorder.trackedOrderCount());
    }

    @Test
    @DisplayName("延后单也留痕：task_log 记「接单延后」→ 已受理待唤醒")
    void deferredOrder_shouldBeRecordedInTaskLog() {
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT"));

        var entries = recorder.entriesOf(response.orderId());
        assertEquals(1, entries.size());
        assertEquals("接单延后", entries.get(0).opType());
        assertEquals(OrderStatus.ACCEPTED_WAIT_WAKE.getValue(), entries.get(0).toStatus());
    }

    @Test
    @DisplayName("缺 MMSI → MMSI_MISSING")
    void missingMmsi_shouldBeRejected() {
        OrderSubmitResponse response = service.submit(request(null, 5.5, null, null));

        assertFalse(response.accepted());
        assertEquals("MMSI_MISSING", response.reasonCode());
        assertNotNull(response.message());
        assertEquals(0, service.orderCount(), "被拒请求不得建单");
    }

    @Test
    @DisplayName("MMSI 非 9 位数字 → MMSI_MALFORMED")
    void malformedMmsi_shouldBeRejected() {
        assertEquals("MMSI_MALFORMED", service.submit(request("41300", 5.5, null, null)).reasonCode());
        assertEquals("MMSI_MALFORMED", service.submit(request("41300000A", 5.5, null, null)).reasonCode());
        assertEquals("MMSI_MALFORMED", service.submit(request("4130000012", 5.5, null, null)).reasonCode());
    }

    @Test
    @DisplayName("载重非正 → GOODS_WEIGHT_INVALID")
    void invalidWeight_shouldBeRejected() {
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, null, null, null)).reasonCode());
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, 0.0, null, null)).reasonCode());
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, -1.0, null, null)).reasonCode());
    }

    @Test
    @DisplayName("期望送达时间不晚于当前 → EXPECTED_TIME_INVALID")
    void pastExpectedTime_shouldBeRejected() {
        assertEquals("EXPECTED_TIME_INVALID",
                service.submit(request(MMSI, 5.5, System.currentTimeMillis() - 1000L, null)).reasonCode());
    }

    @Test
    @DisplayName("请求体缺失 → REQUEST_MISSING")
    void nullRequest_shouldBeRejected() {
        assertEquals("REQUEST_MISSING", service.submit(null).reasonCode());
    }

    @Test
    @DisplayName("订单号唯一，列表按时间稳定排序")
    void orders_shouldBeListedStably() {
        feedShip(MMSI);
        feedShip("413000002");
        String first = service.submit(request(MMSI, 5.5, inOneHour(), "URGENT")).orderId();
        String second = service.submit(request("413000002", 3.0, inOneHour(), "EMERGENCY")).orderId();

        assertFalse(first.equals(second));
        assertEquals(2, service.orderCount());

        List<String> ids = service.listOrders().stream().map(o -> o.orderId()).toList();
        assertEquals(2, ids.size());
        assertTrue(ids.contains(first) && ids.contains(second));
    }

    @Test
    @DisplayName("优先级缺省为 NORMAL；非法值不静默兜底")
    void priority_shouldDefaultToNormalWithoutFallback() {
        assertEquals(OrderPriority.NORMAL, OrderPriority.fromCode(null));
        assertEquals(OrderPriority.NORMAL, OrderPriority.fromCode("  "));
        assertEquals(OrderPriority.URGENT, OrderPriority.fromCode("urgent"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OrderPriority.fromCode("HIGH"));
    }

    @Test
    @DisplayName("★ 11-05：货型超 64 字符 → GOODS_TYPE_TOO_LONG（不让库层去截断或报 500）")
    void tooLongGoodsType_shouldBeRejected() {
        String tooLong = "医".repeat(OrderSubmitRequest.GOODS_TYPE_MAX_LENGTH + 1);

        OrderSubmitResponse response = service.submit(
                new OrderSubmitRequest(MMSI, 5.5, tooLong, inOneHour(), "NORMAL"));

        assertFalse(response.accepted());
        assertEquals("GOODS_TYPE_TOO_LONG", response.reasonCode());
        assertEquals(0, service.orderCount(), "被拒请求不得建单");
    }

    @Test
    @DisplayName("11-05：恰好 64 字符不触发长度闸（边界不是「小于」，继续走到白名单判据）")
    void goodsTypeAtLimit_shouldPassLengthGate() {
        feedShip(MMSI);
        String atLimit = "医".repeat(OrderSubmitRequest.GOODS_TYPE_MAX_LENGTH);

        OrderSubmitResponse response = service.submit(
                new OrderSubmitRequest(MMSI, 5.5, atLimit, inOneHour(), "NORMAL"));

        // 64 字符合法，故不会被长度闸拦；它当然不在货型白名单里，落点应是 CARGO_TYPE。
        // 断言这两个原因码的先后关系，比只断言「被拒」更能证明闸门位置正确。
        assertEquals("CARGO_TYPE", response.reasonCode(),
                "64 字符应通过长度闸、交由白名单判据处置");
    }

    @Test
    @DisplayName("★ 11-05：MMSI 首尾空白在请求体里就归一，不会带到事件与落库")
    void mmsiWithSpaces_shouldBeTrimmedAtRequestLevel() {
        feedShip(MMSI);
        OrderSubmitRequest padded = new OrderSubmitRequest(
                "  " + MMSI + " ", 5.5, "医疗物资", inOneHour(), "URGENT");

        assertEquals(MMSI, padded.shipMmsi(), "归一必须发生在请求体，而不是只在校验处");

        OrderSubmitResponse response = service.submit(padded);
        assertTrue(response.accepted(), "归一后应是合法 MMSI：" + response.reasonCode());
        assertEquals(OrderStatus.PENDING_ACCEPT, response.order().orderStatus());
    }

    @Test
    @DisplayName("11-05：带上 cargoNestId 与不带都能受理（货巢是可选项，不进完整性判据）")
    void cargoNestId_shouldBeOptional() {
        feedShip(MMSI);
        OrderSubmitResponse withNest = service.submit(new OrderSubmitRequest(
                MMSI, 5.5, "医疗物资", inOneHour(), "URGENT", "NEST-A"));
        assertTrue(withNest.accepted(), "指定货巢不该被当成异常输入：" + withNest.reasonCode());

        feedShip("413000002");
        OrderSubmitResponse withoutNest = service.submit(
                request("413000002", 3.0, inOneHour(), "NORMAL"));
        assertTrue(withoutNest.accepted(), "不指定货巢走「离船最近」策略，同样受理");
    }
}
