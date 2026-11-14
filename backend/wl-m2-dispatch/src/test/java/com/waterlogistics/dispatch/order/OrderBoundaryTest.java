package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 订单边界的两个「脏值不落库」判据（11-14 序号 2-6 · HIGH.19 / MEDIUM.30）。
 *
 * <p>两条都关于<b>同一件事的两头</b>：值从请求进来到写进 {@code order_task}，
 * 中间任何一处放过，库里就会多一条「看着合法、语义错了」的行，而事后无从分辨
 * 是输入错还是落库错。
 */
class OrderBoundaryTest {

    // ───────────── HIGH.19：优先级在事件边界归一 ─────────────

    /** 10 参构造（不带货巢），便于逐条只改优先级。 */
    private static OrderSubmittedEvent eventWithPriority(String priority) {
        return new OrderSubmittedEvent("ORD-1", "413000001", 5.5, "医疗物资", null, priority,
                1_794_268_800_000L, null, null, null);
    }

    @Test
    @DisplayName("★ HIGH.19：大小写与空格在事件边界被归一为枚举名")
    void priority_shouldBeNormalizedToEnumName() {
        assertEquals("URGENT", eventWithPriority(" urgent ").priority(),
                "空白与大小写不得透传到 order_task.priority");
        assertEquals("EMERGENCY", eventWithPriority("Emergency").priority());
        assertEquals("NORMAL", eventWithPriority("NORMAL").priority());
    }

    @Test
    @DisplayName("★ HIGH.19：优先级缺省按列默认值 NORMAL，与 OrderPriority.fromCode 同口径")
    void blankPriority_shouldFallBackToNormal() {
        assertEquals("NORMAL", eventWithPriority(null).priority());
        assertEquals("NORMAL", eventWithPriority("   ").priority());
    }

    @Test
    @DisplayName("★ HIGH.19：取值域外的优先级直接抛，不静默兜底成 NORMAL")
    void unknownPriority_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> eventWithPriority("加急"));
        assertThrows(IllegalArgumentException.class, () -> eventWithPriority("HIGH"));
    }

    // ───────────── MEDIUM.30：发布失败时内存视图跟着回退 ─────────────

    /** 只让「订单已受理」事件失败：模拟 DispatchTrigger 落库抛异常后重新抛出。 */
    private static ApplicationEventPublisher failingPublisher(AtomicBoolean thrown) {
        return event -> {
            if (event instanceof OrderSubmittedEvent) {
                thrown.set(true);
                throw new IllegalStateException("模拟 DispatchTrigger 落库失败");
            }
        };
    }

    private static OrderAdmissionService serviceWith(ApplicationEventPublisher publisher,
            TaskLogRecorder recorder) {
        PredictionService predictionService = new PredictionService(
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 1L, 840L),
                mmsi -> Optional.empty(),
                event -> { });
        NestMapper nestMapper = (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> "selectList".equals(method.getName())
                        ? List.<NestEntity>of() : null);
        AdmissionInputAssembler assembler = new AdmissionInputAssembler(
                predictionService, nestMapper, new DispatchGateService(),
                "115.852,28.646;115.859,28.663;115.879,28.692", 0.0, 0.0, 50.0,
                "医疗物资,应急药品,食品,生活物资", 50.0, 0.1, 10_000L, 6.0, 60.0,
                60L, 60L, 1800L, 0.5, 300L, 1800L);
        AdmissionWakeScheduler wakeScheduler = new AdmissionWakeScheduler(null) {
            @Override
            public void scheduleWake(String orderId, long wakeAtMs) {
                // 本用例不验定时唤醒
            }
        };
        OrderTaskMapper orderTaskMapper = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == List.class) {
                        return List.of();
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return rt == boolean.class ? false : null;
                });
        return new OrderAdmissionService(recorder, publisher, assembler, wakeScheduler,
                orderTaskMapper);
    }

    @Test
    @DisplayName("★ MEDIUM.30：落库事件发布失败 ⇒ 内存视图不留这条单，且异常原样上抛")
    void publishFailure_shouldRollbackInMemoryOrder() {
        AtomicBoolean thrown = new AtomicBoolean();
        TaskLogRecorder recorder = new TaskLogRecorder();
        OrderAdmissionService service = serviceWith(failingPublisher(thrown), recorder);

        // 不喂船位 ⇒ 走延后分支，但同样会发布「订单已受理」事件
        OrderSubmitRequest request = new OrderSubmitRequest(
                "413000001", 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT");

        assertThrows(IllegalStateException.class, () -> service.submit(request),
                "下游失败必须让调用方看见，不能吞成 200");

        assertTrue(thrown.get(), "前提：失败的确实是「订单已受理」那一次发布");
        assertTrue(service.listOrders().isEmpty(),
                "库里没有这单，内存视图也不许留着——否则按订单号能查到一个不存在的单");
        // 留痕那条保持不动：它是审计流，不是真相来源；真相以 order_task 为准
        assertEquals(1, recorder.trackedOrderCount(), "task_log 留痕照记，用于事后追这条失败的受理");
    }

    @Test
    @DisplayName("★ MEDIUM.30：发布失败前后，服务对「有没有这单」的回答必须一致")
    void publishFailure_shouldKeepMemoryConsistentWithResponse() {
        OrderAdmissionService service = serviceWith(failingPublisher(new AtomicBoolean()),
                new TaskLogRecorder());

        OrderSubmitRequest request = new OrderSubmitRequest(
                "413000002", 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT");

        assertThrows(IllegalStateException.class, () -> service.submit(request));
        // 同一个服务再查一次：不能因为上一单失败过就残留状态
        assertTrue(service.listOrders().isEmpty());
        assertTrue(service.listOrders().stream()
                        .noneMatch(view -> view.orderStatus() == OrderStatus.PENDING_ACCEPT),
                "没有任何一单停在「待受理」却不在库里");
    }

    // ───────────── MEDIUM.24 / 34 / 35：已在后续合并请求修好，这里补钉 ─────────────

    @Test
    @DisplayName("★ MEDIUM.24：NaN 的交接时长与重量容差在策略构造时被拒")
    void policy_shouldRejectNonFinite() {
        // 交接时长 NaN：旧写法只判 `< 0.0`，NaN 一路进 EtaLowerBound.compute 才抛，
        // 那时整个判定链已经炸在业务路径上，而不是装配期
        assertThrows(IllegalArgumentException.class, () -> new AdmissionPipeline.Policy(
                java.util.Set.of("医疗物资"), 25.0, 0.05, 120_000L, 20.0, Double.NaN,
                60L, 300L, 1800L, 0.5, 3600L));
        // 重量容差 NaN：旧写法会让「重量差 > NaN」恒 false，窗内所有订单都被判重复
        assertThrows(IllegalArgumentException.class, () -> new AdmissionPipeline.Policy(
                java.util.Set.of("医疗物资"), 25.0, 0.05, 120_000L, 20.0, 60.0,
                60L, 300L, 1800L, Double.NaN, 3600L));
    }

    @Test
    @DisplayName("★ MEDIUM.34：MMSI 两侧空白在请求体就被去掉，不会带着空格落库")
    void paddedMmsi_shouldBeAcceptedAfterTrim() {
        OrderAdmissionService service = serviceWith(event -> { }, new TaskLogRecorder());
        OrderSubmitRequest request = new OrderSubmitRequest(
                " 413000001 ", 5.5, "医疗物资", System.currentTimeMillis() + 3_600_000L, "URGENT");

        assertEquals("413000001", request.shipMmsi(), "归一在记录体，消费方看到的就是干净值");

        // 带空格的船号不得被判成「非法 MMSI」拒单：无船数据时应走延后
        OrderSubmitResponse response = service.submit(request);
        assertTrue(response.accepted(), "带空白的合法 MMSI 不是非法输入");
        assertEquals("DATA_STALE", response.reasonCode(), "真正的拒绝原因应是数据不新鲜");
    }

    @Test
    @DisplayName("★ MEDIUM.35：货型长度超列宽在接单判定里被拒，不让它到库里才炸")
    void oversizedGoodsType_shouldBeRejectedAtAdmission() {
        OrderAdmissionService service = serviceWith(event -> { }, new TaskLogRecorder());
        String tooLong = "医".repeat(OrderSubmitRequest.GOODS_TYPE_MAX_LENGTH + 1);
        OrderSubmitRequest request = new OrderSubmitRequest(
                "413000001", 5.5, tooLong, System.currentTimeMillis() + 3_600_000L, "URGENT");

        OrderSubmitResponse response = service.submit(request);

        assertFalse(response.accepted(), "超列宽必须在受理阶段就被拒，而不是落库时 500");
        assertEquals("GOODS_TYPE_TOO_LONG", response.reasonCode());
        assertTrue(service.listOrders().isEmpty(), "被拒的单不建内存视图");
    }
}
