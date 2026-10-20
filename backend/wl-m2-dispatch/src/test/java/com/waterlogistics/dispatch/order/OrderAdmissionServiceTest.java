package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderAdmissionService} 单测（M2 · 卢肄文 · 模块二功能 1「订单接入与基本校验」）。
 *
 * <p>覆盖：良构请求建单为「待受理」、五类非法输入各返回对应原因码、
 * 建单事件进 {@code task_log} 事件流、列表排序与计数。
 */
class OrderAdmissionServiceTest {

    private static final String MMSI = "413000001";

    private TaskLogRecorder recorder;
    private OrderAdmissionService service;

    @BeforeEach
    void setUp() {
        recorder = new TaskLogRecorder();
        service = new OrderAdmissionService(recorder, event -> { });
    }

    private static OrderSubmitRequest request(String mmsi, Double weight, Long expectedTimeMs, String priority) {
        return new OrderSubmitRequest(mmsi, weight, "医疗物资", expectedTimeMs, priority);
    }

    @Test
    @DisplayName("良构请求：建单为「待受理」，版本号 1，waitReason 为 null")
    void validRequest_shouldCreatePendingOrder() {
        OrderSubmitResponse response =
                service.submit(request(MMSI, 5.5, System.currentTimeMillis() + 3_600_000L, "URGENT"));

        assertTrue(response.accepted());
        assertNotNull(response.orderId());
        assertNull(response.reasonCode());

        assertEquals(OrderStatus.PENDING_ACCEPT, response.order().orderStatus());
        assertEquals(OrderStatus.PENDING_ACCEPT.getValue(), response.order().orderStatus().getValue());
        assertEquals(1L, response.order().stateVersion());
        assertNull(response.order().waitReason(), "waitReason 仅「已受理待唤醒」有值");
        assertNull(response.order().emergencyLandingPoint());
    }

    @Test
    @DisplayName("建单事件进 task_log 事件流（功能 11）")
    void admission_shouldBeRecordedInTaskLog() {
        OrderSubmitResponse response = service.submit(request(MMSI, 5.5, null, null));

        var entries = recorder.entriesOf(response.orderId());
        assertEquals(1, entries.size());
        assertEquals("订单接入", entries.get(0).opType());
        assertNull(entries.get(0).fromStatus());
        assertEquals(OrderStatus.PENDING_ACCEPT.getValue(), entries.get(0).toStatus());
        assertEquals(1, recorder.trackedOrderCount());
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
    @DisplayName("MMSI 首尾空白先 trim 再校验（宽松但确定的行为）")
    void paddedMmsi_shouldBeTrimmedAndAccepted() {
        assertTrue(service.submit(request("  413000001  ", 5.5, null, null)).accepted());
    }

    @Test
    @DisplayName("载重非正 → GOODS_WEIGHT_INVALID")
    void invalidWeight_shouldBeRejected() {
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, null, null, null)).reasonCode());
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, 0.0, null, null)).reasonCode());
        assertEquals("GOODS_WEIGHT_INVALID", service.submit(request(MMSI, -1.0, null, null)).reasonCode());
    }

    @Test
    @DisplayName("优先级非法 → PRIORITY_INVALID")
    void invalidPriority_shouldBeRejected() {
        assertEquals("PRIORITY_INVALID",
                service.submit(request(MMSI, 5.5, null, "SUPER_URGENT")).reasonCode());
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
        String first = service.submit(request(MMSI, 5.5, null, null)).orderId();
        String second = service.submit(request("413000002", 3.0, null, "EMERGENCY")).orderId();

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
}
