package com.waterlogistics.dispatch.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.queue.CargoNestGate;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
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

    private SysParamLoader sysParamLoader;

    private WaitReasonAlertEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        alertTable.clear();
        mqMessages.clear();
        orderRow = new OrderTaskEntity();
        orderRow.setOrderId(ORDER_ID);
        orderRow.setDroneId(DRONE_ID);

        sysParamLoader = mock(SysParamLoader.class);
        when(sysParamLoader.getInt(eq(CargoNestGate.LOCK_TIMEOUT_KEY), anyInt())).thenReturn(300);

        emitter = new WaitReasonAlertEmitter(alertMapper(), orderTaskMapper(), null, sysParamLoader) {
            @Override
            protected void publish(AlertEventMessage message) {
                mqMessages.add(message);
            }
        };
        setExchange(emitter, "wavenest.data");
    }

    @Test
    @DisplayName("订单进入 WAIT_CARGO 时写一条等待货物到巢告警")
    void waitCargo_shouldEmitCargoTimeoutAlert() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));

        assertEquals(1, alertTable.size());
        AlertEntity row = alertTable.get(0);
        assertEquals(AlertType.WAIT_CARGO_TIMEOUT.getValue(), row.getAlertType());
        assertEquals("等待货物到巢", row.getAlertType());
        assertEquals("警告", row.getLevel());
        assertEquals(ORDER_ID, row.getOrderId());
        assertEquals(DRONE_ID, row.getDroneId());
        assertEquals("未处理", row.getHandleStatus());
        assertEquals(1, mqMessages.size());
        assertEquals(AlertType.WAIT_CARGO_TIMEOUT.getValue(), mqMessages.get(0).alertType());
    }

    /** 宽限秒数从 sys_param 读出并随事件带出，展示名里不含秒数。 */
    @Test
    @DisplayName("等待货物到巢的宽限秒数取自 cargoLockTimeoutSec")
    void waitCargo_shouldCarryGraceSecondsFromSysParam() {
        when(sysParamLoader.getInt(eq(CargoNestGate.LOCK_TIMEOUT_KEY), anyInt())).thenReturn(180);

        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));

        assertEquals(180.0, mqMessages.get(0).thresholdValue().doubleValue(), 1e-9);
        assertFalse(mqMessages.get(0).alertType().contains("180"),
                "展示名不带宽限秒数：" + mqMessages.get(0).alertType());
    }

    @Test
    @DisplayName("订单进入 WAIT_RESOURCE 时写一条等待资源告警")
    void waitResource_shouldEmitWaitResourceAlert() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_RESOURCE));

        assertEquals(1, alertTable.size());
        assertEquals(AlertType.WAIT_RESOURCE.getValue(), alertTable.get(0).getAlertType());
        assertEquals(1, mqMessages.size());
        assertEquals(AlertType.WAIT_RESOURCE.getValue(), mqMessages.get(0).alertType());
        assertNull(mqMessages.get(0).thresholdValue(), "等待资源不带宽限秒数");
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
    @DisplayName("11-06 序号 1：货到巢超时入口已移除，等待原因两类各自覆盖")
    void cargoNestTimeoutEntryRemoved_waitReasonsCoverBothAlerts() {
        emitter.onOrderStateChanged(event(WaitReason.WAIT_CARGO));
        assertEquals(1, alertTable.size());
        assertEquals(AlertType.WAIT_CARGO_TIMEOUT.getValue(), alertTable.get(0).getAlertType());

        emitter.onOrderStateChanged(event(WaitReason.WAIT_RESOURCE));
        assertEquals(2, alertTable.size());
        assertEquals(AlertType.WAIT_RESOURCE.getValue(), alertTable.get(1).getAlertType());
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
        // 11-05 序号 2：OrderStateChangedEvent 现在校验 orderStatus 非空（LOW-MAINT.9）。
        // 带 waitReason 的订单语义就是「已受理待唤醒」，此前传 null 只是夹具偷懒——
        // null 帧推到前端会让状态机读到 undefined，发布侧就该拒掉。
        OrderView view = new OrderView(ORDER_ID, OrderStatus.ACCEPTED_WAIT_WAKE,
                System.currentTimeMillis(), 1L, reason, null, null, null);
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
