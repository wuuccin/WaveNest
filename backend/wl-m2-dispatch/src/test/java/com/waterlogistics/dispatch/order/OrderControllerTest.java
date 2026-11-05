package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.record.TaskLogRecorder;
import com.waterlogistics.storage.repository.GoodsMapper;

import java.lang.reflect.Proxy;
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
        // 本测试只验 GET 回显，不触发 submit／cargo-ready 链路：装配器/唤醒器传 null 即可
        // （本模块测试无 Mockito，JDK 动态代理又只能代理接口，null 是最省的桩）；
        // 货物映射器同理只做占位，不参与本类的任何断言
        controller = new OrderController(
                new OrderAdmissionService(new TaskLogRecorder(), event -> { },
                        null, null, null),
                gateService,
                new CargoReadyService(stubGoodsMapper()));
    }

    /** 全返回 null 的货物映射器桩（本类不断言货物路径）。 */
    private static GoodsMapper stubGoodsMapper() {
        return (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> null);
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

    @Test
    @DisplayName("11-05：cargo-ready 端点转发到 CargoReadyService（桩表全空 → 查无货物）")
    void cargoReady_shouldDelegateToService() {
        CargoReadyResponse response = controller.cargoReady("ORD-1");

        assertEquals(false, response.accepted());
        assertEquals(CargoReadyResponse.Reason.GOODS_NOT_FOUND.name(), response.reasonCode());
        assertEquals("ORD-1", response.orderId(), "订单号要回显，便于前端对号");
    }
}
