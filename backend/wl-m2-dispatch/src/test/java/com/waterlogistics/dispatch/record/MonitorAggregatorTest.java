package com.waterlogistics.dispatch.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MonitorAggregator} 单测（M2 · 卢肄文 · 10-22 任务 D3）。
 *
 * <p>钉死 D3 的两条判据：
 * <b>①空库返回空列表而非报错</b>；<b>②统计可核对</b>（12 种状态全列、缺的补 0）。
 * 另外覆盖字段映射（无人机／起飞时刻／会面点／失败原因）、倒序排序、
 * 会面点缺坐标时不臆造 0,0、脏状态值不吞掉。
 *
 * <p>纯 JUnit，不起 Spring／DB。
 */
class MonitorAggregatorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 22, 9, 0);

    private static OrderTaskEntity order(String orderId, OrderStatus status, LocalDateTime createTime) {
        OrderTaskEntity entity = new OrderTaskEntity();
        entity.setOrderId(orderId);
        entity.setShipMmsi("123456789");
        entity.setGoodsType("医疗物资");
        entity.setGoodsWeight(new BigDecimal("5.5"));
        entity.setPriority("URGENT");
        entity.setStatus(status.getValue());
        entity.setCreateTime(createTime);
        return entity;
    }

    private static OrderTaskEntity dispatchedOrder() {
        OrderTaskEntity entity = order("ORD-1", OrderStatus.DISPATCHED, T0);
        entity.setDroneId("DRN-M400-01");
        entity.setMeetTime(T0.plusMinutes(1));
        entity.setMeetLon(new BigDecimal("114.3030546"));
        entity.setMeetLat(new BigDecimal("30.5999895"));
        return entity;
    }

    @Test
    @DisplayName("★ 判据①：空库返回空列表，不抛异常、total=0")
    void emptyOrders_shouldReturnEmptyList() {
        MonitorAggregator.TasksResponse nullInput = MonitorAggregator.toTasksResponse(null);
        assertEquals(0, nullInput.total());
        assertTrue(nullInput.tasks().isEmpty());

        MonitorAggregator.TasksResponse emptyInput = MonitorAggregator.toTasksResponse(List.of());
        assertEquals(0, emptyInput.total());
        assertTrue(emptyInput.tasks().isEmpty());

        MonitorAggregator.StatisticsResponse stats =
                MonitorAggregator.toStatistics(List.of(), 0L);
        assertEquals(0, stats.totalOrders());
        assertEquals(0, stats.dispatchedCount());
        assertEquals(0, stats.inFlightCount());
        assertEquals(0, stats.finishedCount());
        assertEquals(0, stats.terminalCount());
        assertEquals(0L, stats.taskLogCount());
        assertNull(stats.lastOrderAt());
        // 12 种状态仍要全列（前端口径稳定，不需要自己补）
        assertEquals(OrderStatus.values().length, stats.byStatus().size());
        assertTrue(stats.byStatus().stream().allMatch(c -> c.count() == 0));
    }

    @Test
    @DisplayName("字段映射：无人机／起飞时刻／会面点／载重都要带出来（D3 判据要求列表含这些）")
    void toViews_shouldCarryDispatchFields() {
        List<MonitorAggregator.TaskView> views = MonitorAggregator.toViews(List.of(dispatchedOrder()));
        assertEquals(1, views.size());

        MonitorAggregator.TaskView view = views.get(0);
        assertEquals("ORD-1", view.orderId());
        assertEquals("DISPATCHED", view.statusCode());
        assertEquals(OrderStatus.DISPATCHED.getValue(), view.statusLabel());
        assertEquals("DRN-M400-01", view.droneId());
        assertEquals(T0.plusMinutes(1), view.meetTime());
        assertEquals(new GeoPoint(114.3030546, 30.5999895), view.meetPoint());
        assertEquals(new BigDecimal("5.5"), view.goodsWeightKg());
        assertEquals("URGENT", view.priority());
        assertEquals("123456789", view.shipMmsi());
        assertNull(view.failReason(), "未失败时 failReason 应为 null");
    }

    @Test
    @DisplayName("未派发的单：droneId／meetTime／meetPoint 都是 null（不编造）")
    void pendingOrder_shouldHaveNullDispatchFields() {
        MonitorAggregator.TaskView view = MonitorAggregator.toViews(
                List.of(order("ORD-2", OrderStatus.WAIT_DISPATCH, T0))).get(0);

        assertNull(view.droneId());
        assertNull(view.meetTime());
        assertNull(view.meetPoint());
        assertEquals("WAIT_DISPATCH", view.statusCode());
    }

    @Test
    @DisplayName("会面点只缺一个坐标时整体为 null（不臆造 0,0）")
    void partialMeetPoint_shouldBeNull() {
        OrderTaskEntity entity = order("ORD-3", OrderStatus.DISPATCHED, T0);
        entity.setMeetLon(new BigDecimal("114.30"));
        // meetLat 未设置
        MonitorAggregator.TaskView view = MonitorAggregator.toViews(List.of(entity)).get(0);
        assertNull(view.meetPoint());
    }

    @Test
    @DisplayName("排序：按下单时刻倒序；时刻为 null 的排最后")
    void toViews_shouldSortByCreateTimeDesc() {
        List<OrderTaskEntity> rows = new ArrayList<>();
        rows.add(order("ORD-OLD", OrderStatus.FINISHED, T0));
        rows.add(order("ORD-NEW", OrderStatus.PENDING_ACCEPT, T0.plusHours(2)));
        rows.add(order("ORD-MID", OrderStatus.DISPATCHED, T0.plusHours(1)));
        OrderTaskEntity noTime = order("ORD-NULL", OrderStatus.REJECTED, null);
        rows.add(noTime);

        List<MonitorAggregator.TaskView> views = MonitorAggregator.toViews(rows);

        assertEquals(List.of("ORD-NEW", "ORD-MID", "ORD-OLD", "ORD-NULL"),
                views.stream().map(MonitorAggregator.TaskView::orderId).toList());
    }

    @Test
    @DisplayName("★ 判据②：统计可核对——按状态计数 ＋ 已派发／在飞／完成／终止")
    void statistics_shouldBeVerifiable() {
        List<OrderTaskEntity> rows = List.of(
                order("A", OrderStatus.PENDING_ACCEPT, T0),
                order("B", OrderStatus.DISPATCHED, T0.plusMinutes(1)),
                order("C", OrderStatus.OUTBOUND, T0.plusMinutes(2)),
                order("D", OrderStatus.RETURNING, T0.plusMinutes(3)),
                order("E", OrderStatus.FINISHED, T0.plusMinutes(4)),
                order("F", OrderStatus.REJECTED, T0.plusMinutes(5)));

        MonitorAggregator.StatisticsResponse stats = MonitorAggregator.toStatistics(rows, 17L);

        assertEquals(6, stats.totalOrders());
        assertEquals(1, stats.dispatchedCount());
        assertEquals(2, stats.inFlightCount(), "去程中 + 返航中");
        assertEquals(1, stats.finishedCount());
        assertEquals(1, stats.terminalCount(), "已拒绝");
        assertEquals(17L, stats.taskLogCount());
        assertEquals(T0.plusMinutes(5), stats.lastOrderAt(), "最近一单的下单时刻");

        // 逐状态核对（全列、计数对得上）
        assertEquals(1, countOf(stats, OrderStatus.PENDING_ACCEPT));
        assertEquals(1, countOf(stats, OrderStatus.DISPATCHED));
        assertEquals(1, countOf(stats, OrderStatus.OUTBOUND));
        assertEquals(0, countOf(stats, OrderStatus.HANDOVER));
        assertEquals(1, countOf(stats, OrderStatus.RETURNING));
        assertEquals(1, countOf(stats, OrderStatus.FINISHED));
        assertEquals(1, countOf(stats, OrderStatus.REJECTED));
        // 计数之和 == 总数（可核对性的核心）
        int sum = stats.byStatus().stream().mapToInt(MonitorAggregator.StatusCount::count).sum();
        assertEquals(stats.totalOrders(), sum);
    }

    @Test
    @DisplayName("脏状态值：不抛异常、不被吞掉——statusCode=UNKNOWN 且统计里单列一条")
    void unknownStatus_shouldBeVisible() {
        OrderTaskEntity dirty = order("ORD-X", OrderStatus.WAIT_DISPATCH, T0);
        dirty.setStatus("历史脏值");

        MonitorAggregator.TaskView view = MonitorAggregator.toViews(List.of(dirty)).get(0);
        assertEquals(MonitorAggregator.UNKNOWN_CODE, view.statusCode());
        assertEquals("历史脏值", view.statusLabel(), "原值要照原样回显，别吞");

        MonitorAggregator.StatisticsResponse stats = MonitorAggregator.toStatistics(List.of(dirty), 0L);
        assertEquals(1, stats.totalOrders());
        assertEquals(0, stats.dispatchedCount());
        MonitorAggregator.StatusCount unknown = stats.byStatus().stream()
                .filter(c -> MonitorAggregator.UNKNOWN_CODE.equals(c.code()))
                .findFirst().orElseThrow();
        assertEquals(1, unknown.count());
        assertEquals(OrderStatus.values().length + 1, stats.byStatus().size(),
                "有脏值时多一条 UNKNOWN；无脏值时不多");
    }

    @Test
    @DisplayName("空状态值也按 UNKNOWN 处理，不抛异常")
    void blankStatus_shouldBeUnknown() {
        OrderTaskEntity blank = order("ORD-B", OrderStatus.PENDING_ACCEPT, T0);
        blank.setStatus(null);
        MonitorAggregator.TaskView view = MonitorAggregator.toViews(List.of(blank)).get(0);
        assertEquals(MonitorAggregator.UNKNOWN_CODE, view.statusCode());
        assertNull(view.statusLabel());
    }

    @Test
    @DisplayName("列表里的 null 行被跳过，不影响其余行与计数")
    void nullRows_shouldBeSkipped() {
        List<OrderTaskEntity> rows = new ArrayList<>();
        rows.add(order("OK-1", OrderStatus.DISPATCHED, T0));
        rows.add(null);

        MonitorAggregator.TasksResponse response = MonitorAggregator.toTasksResponse(rows);
        assertEquals(1, response.total());
        assertEquals(1, response.tasks().size());
        assertFalse(response.tasks().isEmpty());
    }

    private static int countOf(MonitorAggregator.StatisticsResponse stats, OrderStatus status) {
        return stats.byStatus().stream()
                .filter(c -> status.name().equals(c.code()))
                .findFirst()
                .orElseThrow()
                .count();
    }
}
