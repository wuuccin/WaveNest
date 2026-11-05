package com.waterlogistics.dispatch.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WaitReasonAlertEmitter} 单测（M2 · 宋冰鑫 · 11-05 序号 2）。
 *
 * <p>验收点：三类等待各写一条 alert 记录并走 alert.event MQ；沿用 alert 表现有列，
 * 不新增表、不新增列；同一订单同一等待原因在一次等待窗口内只发一条。
 */
class WaitReasonAlertEmitterTest {

    private static final String ORDER_ID = "ORD-1";
    private static final String DRONE_ID = "UAV-01";

    private final List<AlertEntity> alertTable = new ArrayList<>();
    private final List<AlertEventMessage> mqMessages = new ArrayList<>();
    private OrderTaskEntity orderRow;

    private WaitReasonAlertEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        alertTable.clear();
        mqMessages.clear();
        orderRow = new OrderTaskEntity();
        orderRow.setOrderId(ORDER_ID);
        orderRow.setDroneId(DRONE_ID);

        emitter = new WaitReasonAlertEmitter(alertMapper(), orderTaskMapper(), null) {
            @Override
            protected void publish(AlertEventMessage message) {
                mqMessages.add(message);
            }
        };
        setExchange(emitter, "wavenest.data");
    }

    @Test
    @DisplayName("订单进入 WAIT_CARGO 时写一条装货超时告警")
    void waitCargo_shouldEmitCargoTimeoutAlert() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));

        assertEquals(1, alertTable.size());
        AlertEntity row = alertTable.get(0);
        assertEquals(AlertType.WAIT_CARGO_TIMEOUT.getValue(), row.getAlertType());
        assertEquals("警告", row.getLevel());
        assertEquals(ORDER_ID, row.getOrderId());
        assertEquals(DRONE_ID, row.getDroneId());
        assertEquals("未处理", row.getHandleStatus());
        assertEquals(1, mqMessages.size());
        assertEquals(AlertType.WAIT_CARGO_TIMEOUT.getValue(), mqMessages.get(0).alertType());
    }

    @Test
    @DisplayName("订单进入 WAIT_RESOURCE 时写一条等待资源告警")
    void waitResource_shouldEmitWaitResourceAlert() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_RESOURCE));

        assertEquals(1, alertTable.size());
        assertEquals(AlertType.WAIT_RESOURCE.getValue(), alertTable.get(0).getAlertType());
        assertEquals(1, mqMessages.size());
        assertEquals(AlertType.WAIT_RESOURCE.getValue(), mqMessages.get(0).alertType());
    }

    @Test
    @DisplayName("订单离开等待态时不写告警")
    void noWaitReason_shouldNotEmit() {
        emitter.onOrderStateChanged(event(null));
        emitter.onOrderStateChanged(event(WaitReason.WAIT_SHIP));
        emitter.onOrderStateChanged(event(WaitReason.GATE_OR_DATA));

        assertTrue(alertTable.isEmpty());
        assertTrue(mqMessages.isEmpty());
    }

    @Test
    @DisplayName("同一等待原因在一次等待窗口内只发一条告警")
    void sameWaitReason_shouldDeduplicate() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));

        assertEquals(1, alertTable.size());
        assertEquals(1, mqMessages.size());
    }

    @Test
    @DisplayName("离开等待态后清除去重标记，再次进入可重新告警")
    void leavingWait_shouldClearDedup() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));
        assertEquals(1, alertTable.size());

        emitter.onOrderStateChanged(event(null));
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));
        assertEquals(2, alertTable.size());
    }

    @Test
    @DisplayName("emitCargoNestTimeout 写一条货到巢超时告警")
    void cargoNestTimeout_shouldEmitCargoNestTimeoutAlert() {
        emitter.emitCargoNestTimeout(ORDER_ID);

        assertEquals(1, alertTable.size());
        assertEquals(AlertType.CARGO_NEST_TIMEOUT.getValue(), alertTable.get(0).getAlertType());
        assertEquals(1, mqMessages.size());
        assertEquals(AlertType.CARGO_NEST_TIMEOUT.getValue(), mqMessages.get(0).alertType());
    }

    @Test
    @DisplayName("订单查不到 droneId 时 alert 行 droneId 为 null，但仍写告警")
    void missingDrone_shouldStillEmitWithNullDrone() {
        orderRow = null;
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));

        assertEquals(1, alertTable.size());
        assertNull(alertTable.get(0).getDroneId());
    }

    private OrderStateChangedEvent event(WaitReason reason) {
        OrderView view = new OrderView(ORDER_ID, null, System.currentTimeMillis(), 1L,
                reason, null, null, null);
        return new OrderStateChangedEvent(view);
    }

    private AlertMapper alertMapper() {
        return (AlertMapper) Proxy.newProxyInstance(
                AlertMapper.class.getClassLoader(), new Class<?>[]{AlertMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        alertTable.add((AlertEntity) args[0]);
                        return 1;
                    }
                    return null;
                });
    }

    private OrderTaskMapper orderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return orderRow;
                    }
                    return null;
                });
    }

    private static void setExchange(WaitReasonAlertEmitter emitter, String value) throws Exception {
        Field field = WaitReasonAlertEmitter.class.getDeclaredField("exchange");
        field.setAccessible(true);
        field.set(emitter, value);
    }
}
