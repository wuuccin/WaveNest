package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderController} 单测（M2 · 卢肄文）——<b>P0-6 闸门摘要回显的守门测试</b>。
 *
 * <p>验收要点：{@code GET /api/orders} 的 {@code dispatchGate} <b>必须反映
 * {@code DispatchGateService} 的真实状态</b>，不得再是硬编码的「正常」。
 * 本测试通过 {@code trip}/{@code clear} 改变闸门状态，断言接口摘要随之变化——
 * 这正是 P0-6 的完成判据（《场景对齐方案 v3》P0-6）。
 *
 * <p>不依赖 Spring 与数据库：直接 new 出被测对象。
 */
class OrderControllerTest {

    private DispatchGateService gateService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        gateService = new DispatchGateService();
        controller = new OrderController(
                new OrderAdmissionService(new TaskLogRecorder()), gateService);
    }

    @Test
    @DisplayName("★ P0-6：闸门正常时，接口回显「正常」且来源为空")
    void gate_shouldEchoNormal() {
        DispatchGate gate = controller.listOrders().dispatchGate();

        assertEquals(GateStatus.NORMAL, gate.gateStatus());
        assertTrue(gate.gateSources().isEmpty());
        assertEquals(false, gate.isBroken());
    }

    @Test
    @DisplayName("★ P0-6：闸门熔断后，接口回显「熔断中」＋ 来源（证明不是硬编码）")
    void gate_shouldEchoBrokenAfterTrip() {
        gateService.trip(GateSource.AUTO);

        DispatchGate gate = controller.listOrders().dispatchGate();

        assertEquals(GateStatus.BROKEN, gate.gateStatus());
        assertEquals(1, gate.gateSources().size());
        assertEquals(GateSource.AUTO, gate.gateSources().get(0));
        assertTrue(gate.isBroken());
    }

    @Test
    @DisplayName("★ P0-6：解除后恢复「正常」（状态是活的，不是一次性快照）")
    void gate_shouldEchoNormalAfterClear() {
        gateService.trip(GateSource.MANUAL);
        assertEquals(GateStatus.BROKEN, controller.listOrders().dispatchGate().gateStatus());

        gateService.clear(GateSource.MANUAL);
        assertEquals(GateStatus.NORMAL, controller.listOrders().dispatchGate().gateStatus());
    }

    @Test
    @DisplayName("两种来源叠加：都解除才恢复（22 §3.2：人力冻结不覆盖自动熔断）")
    void gate_shouldRequireAllSourcesCleared() {
        gateService.trip(GateSource.AUTO);
        gateService.trip(GateSource.MANUAL);
        assertEquals(2, controller.listOrders().dispatchGate().gateSources().size());

        gateService.clear(GateSource.MANUAL);
        DispatchGate afterManualClear = controller.listOrders().dispatchGate();
        assertEquals(GateStatus.BROKEN, afterManualClear.gateStatus(),
                "自动熔断仍有效，闸门不得恢复");
        assertEquals(1, afterManualClear.gateSources().size());

        gateService.clear(GateSource.AUTO);
        assertEquals(GateStatus.NORMAL, controller.listOrders().dispatchGate().gateStatus());
    }
}
