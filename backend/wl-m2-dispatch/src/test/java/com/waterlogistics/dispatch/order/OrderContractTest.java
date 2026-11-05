package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.contract.state.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订单侧契约 v2 的字段级单测（M2 · 卢肄文 · 11-05 序号 1）。
 *
 * <p>这些断言看起来琐碎，但它们守的是<b>跨人契约</b>：张睿哲按
 * {@code AdmissionPipeline.OrderRequest} 取货巢、杨添爻按 {@code cargoNestId} 做
 * 巢×机枚举、吴可欣按 {@code etaTs} 填视图。任何一处名字或可空性变了，
 * 是别人的代码编译不过或静默取到 null——比一次单测失败贵得多，所以在这里钉死。
 */
class OrderContractTest {

    private static final long ETA = 1_760_000_900_000L;

    private static OrderView view(String orderId, Long etaTs) {
        return new OrderView(orderId, OrderStatus.PENDING_ACCEPT, 1_760_000_000_000L, 1L,
                null, null, null, null, etaTs);
    }

    @Test
    @DisplayName("★ 请求归一：MMSI／货型／货巢一律 trim，全空白视同未填")
    void request_shouldNormalizeTextFields() {
        OrderSubmitRequest request = new OrderSubmitRequest(
                "  413000001 ", 5.5, "  医疗物资  ", null, " urgent ", "  NEST-A ");

        assertEquals("413000001", request.shipMmsi());
        assertEquals("医疗物资", request.goodsType());
        assertEquals("NEST-A", request.cargoNestId());
        assertTrue(request.hasCargoNest());
        // 优先级串不在此归一：它由 OrderPriority.fromCode 一次解析成枚举，落库与事件都写枚举名（HIGH.18）
        assertEquals(OrderPriority.URGENT, OrderPriority.fromCode(request.priority()));
    }

    @Test
    @DisplayName("全空白的可选字段归一成 null（库里不出现『有值但为空』）")
    void blankOptionalFields_shouldBecomeNull() {
        OrderSubmitRequest request = new OrderSubmitRequest(
                "413000001", 5.5, "   ", null, null, "\t");

        assertNull(request.goodsType());
        assertNull(request.cargoNestId());
        assertFalse(request.hasCargoNest());
    }

    @Test
    @DisplayName("兼容：5 参构造等价于「未指定货巢」，expectedTimeMs 可为 null")
    void legacyConstructor_shouldKeepCargoNestNull() {
        OrderSubmitRequest request =
                new OrderSubmitRequest("413000001", 5.5, "医疗物资", null, "NORMAL");

        assertNull(request.cargoNestId());
        assertFalse(request.hasCargoNest());
        assertNull(request.expectedTimeMs(), "期望送达可不填，字段本身允许 null");
    }

    @Test
    @DisplayName("★ 优先级取值序 NORMAL=1 < URGENT=2 < EMERGENCY=3（队列取单与延误惩罚共用）")
    void priorityRank_shouldBeAscendingByUrgency() {
        assertEquals(1, OrderPriority.NORMAL.rank());
        assertEquals(2, OrderPriority.URGENT.rank());
        assertEquals(3, OrderPriority.EMERGENCY.rank());
        assertTrue(OrderPriority.EMERGENCY.rank() > OrderPriority.NORMAL.rank());
    }

    @Test
    @DisplayName("取值序不随枚举声明顺序漂移（重排枚举不得改变队列次序）")
    void priorityRank_shouldNotDependOnDeclarationOrder() {
        // 按 rank 排序的结果必须稳定为 NORMAL → URGENT → EMERGENCY，
        // 与 values() 的声明顺序无关；若哪天有人把 EMERGENCY 写到最前面，
        // 这条断言仍然通过，说明消费方读的是 rank 而不是 ordinal。
        java.util.List<OrderPriority> byRank = new java.util.ArrayList<>(
                java.util.List.of(OrderPriority.values()));
        byRank.sort(java.util.Comparator.comparingInt(OrderPriority::rank));
        assertEquals(java.util.List.of(OrderPriority.NORMAL, OrderPriority.URGENT,
                OrderPriority.EMERGENCY), byRank);
    }

    @Test
    @DisplayName("★ 响应带 etaTs：受理时取订单视图的值，7 参构造等价于未算出")
    void response_shouldCarryEtaTs() {
        OrderSubmitResponse accepted = OrderSubmitResponse.accepted(view("ORD-1", ETA));
        assertEquals(ETA, accepted.etaTs());

        OrderSubmitResponse deferred = OrderSubmitResponse.deferred(
                view("ORD-2", null), "DATA_STALE", "数据不足", "GATE_OR_DATA", ETA - 1_000L);
        assertNull(deferred.etaTs(), "订单视图没算出送达时刻时，响应也不得造一个");

        OrderSubmitResponse rejected = OrderSubmitResponse.rejected("DUPLICATE", "重复单");
        assertNull(rejected.etaTs());

        OrderSubmitResponse legacy = new OrderSubmitResponse(true, "ORD-3", view("ORD-3", ETA),
                null, null, null, null);
        assertNull(legacy.etaTs(), "旧 7 参构造的语义就是「未算出」");
    }

    @Test
    @DisplayName("★ 管道请求透传货巢；7 参旧构造保持 null（不逼旧调用点同改）")
    void pipelineRequest_shouldTransmitCargoNest() {
        AdmissionPipeline.OrderRequest withNest = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, ETA, "URGENT", null, "NEST-A");
        assertEquals("NEST-A", withNest.cargoNestId());

        AdmissionPipeline.OrderRequest legacy = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, ETA, "URGENT", null);
        assertNull(legacy.cargoNestId());
    }

    @Test
    @DisplayName("货巢与货单号都不进完整性判据（可空字段不该让正常单转人工）")
    void completeness_shouldIgnoreOptionalFields() {
        AdmissionPipeline.OrderRequest base = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, ETA, "URGENT", null);
        assertTrue(base.isComplete(), "货单号为空也要算完整：查货状态另有口径");

        AdmissionPipeline.OrderRequest withNest = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, ETA, "URGENT", null, "NEST-A");
        assertTrue(withNest.isComplete());

        AdmissionPipeline.OrderRequest noDeadline = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, null, "URGENT", null);
        assertTrue(noDeadline.isComplete(),
                "11-05 序号 2：deadline 改由系统算，客户端不填是正常输入，不再转人工");

        AdmissionPipeline.OrderRequest noPriority = new AdmissionPipeline.OrderRequest(
                "ORD-1", "413000001", "医疗物资", 5.5, null, null, null);
        assertFalse(noPriority.isComplete(), "优先级仍是必填，缺了才转人工补录");
    }

    @Test
    @DisplayName("端点路径走常量：模板与按单号生成的路径一致")
    void cargoReadyPath_shouldFollowContract() {
        assertEquals("/api/orders/{orderId}/cargo-ready", ApiPath.CARGO_READY_TEMPLATE);
        assertEquals("/api/orders/ORD-1/cargo-ready", ApiPath.cargoReady("ORD-1"));
    }
}
