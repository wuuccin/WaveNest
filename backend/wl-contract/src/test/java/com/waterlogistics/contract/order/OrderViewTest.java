package com.waterlogistics.contract.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderView} 契约测试，钉住预计送达时刻、货物所在巢与配送品类三个字段。
 *
 * <p>为什么单独钉：订单视图是 REST 快照与 WS {@code order_state} 帧共用的对象，
 * 字段集一改就同时影响两端；组件数与末位字段名在这里钉死，改动须走裁决。
 */
class OrderViewTest {

    @Test
    @DisplayName("字段集：十一个组件，末位是配送品类 goodsType")
    void components_shouldBeElevenWithGoodsTypeLast() {
        RecordComponent[] components = OrderView.class.getRecordComponents();

        assertEquals(11, components.length, "订单视图组件数必须是十一，末位为配送品类");
        assertEquals("etaTs", components[8].getName());
        assertEquals(Long.class, components[8].getType(), "etaTs 可空，用包装类型 Long");
        assertEquals("cargoNestId", components[9].getName());
        assertEquals(String.class, components[9].getType(), "货巢编号是字符串，可空");
        assertEquals("goodsType", components[10].getName());
        assertEquals(String.class, components[10].getType(), "配送品类是字符串，可空");
    }

    @Test
    @DisplayName("旧构造点不受影响：etaTs 留空")
    void legacyConstructor_shouldLeaveEtaTsNull() {
        OrderView view = new OrderView("ORD-1", OrderStatus.DISPATCHED, 1000L, 2L,
                null, null, null, null);

        assertNull(view.etaTs(), "未算出送达时刻时必须是 null");
        assertNull(view.cargoNestId(), "未选定货巢时必须是 null");
        assertNull(view.goodsType(), "兼容构造器不得猜补默认品类");
        assertEquals("ORD-1", view.orderId());
        assertEquals(OrderStatus.DISPATCHED, view.orderStatus());
    }

    @Test
    @DisplayName("带 etaTs 的构造：预计送达时刻原样带出，货巢留空")
    void constructor_shouldCarryEtaTs() {
        OrderView view = new OrderView("ORD-2", OrderStatus.WAIT_DISPATCH, 1000L, 3L,
                null, null, null, null, 1_700_000_000_000L);

        assertEquals(1_700_000_000_000L, view.etaTs().longValue());
        assertNull(view.cargoNestId(), "未选定货巢时必须是 null");
    }

    @Test
    @DisplayName("带货巢的构造：系统选定的货巢原样带出")
    void constructor_shouldCarryCargoNestId() {
        OrderView view = new OrderView("ORD-3", OrderStatus.ACCEPTED_WAIT_WAKE, 1000L, 1L,
                WaitReason.WAIT_CARGO, null, null, null, null, "NEST-02");

        assertEquals("NEST-02", view.cargoNestId());
        assertEquals(WaitReason.WAIT_CARGO, view.waitReason());
        assertNull(view.goodsType(), "历史订单缺值时必须是 null");
    }

    @Test
    @DisplayName("生产构造点显式携带配送品类")
    void constructor_shouldCarryGoodsType() {
        OrderView view = new OrderView("ORD-4", OrderStatus.PENDING_ACCEPT, 1000L, 1L,
                null, null, null, null, null, "NEST-02", "应急药品");

        assertEquals("应急药品", view.goodsType());
        assertEquals("NEST-02", view.cargoNestId());
    }
}
