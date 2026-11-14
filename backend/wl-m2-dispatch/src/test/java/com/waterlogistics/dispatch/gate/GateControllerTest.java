package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.rest.InterveneRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GateController} 单测（D2 · 2026-10-22；序号 3 扩到八动作）——
 * <b>请求体 → 动作分派 → 响应确认</b>。
 *
 * <p>不依赖 Spring、不依赖 mock 框架：直接 new 出被测对象与真实
 * {@link DispatchGateService}，订单干预出口传一个记录型 lambda
 * （{@link OrderInterventionService} 是函数式接口）。本类只测控制器的
 * 归一、校验、分派与响应组装；404／409 由实现方抛出，本类验其不被吞掉。
 *
 * <p>覆盖：客户端错误 400、动作词归一、来源仅 MANUAL、reset 保留自动熔断
 * （HIGH.12 / HIGH.13 / MEDIUM.21 / MEDIUM.22），以及《1112》§3.1 至 §3.4。
 */
class GateControllerTest {

    private DispatchGateService service;
    private GateController controller;
    private String lastAction;
    private String lastOrderId;

    @BeforeEach
    void setUp() {
        service = new DispatchGateService((action, orderId) -> {
            lastAction = action;
            lastOrderId = orderId;
        });
        controller = new GateController(service);
        lastAction = null;
        lastOrderId = null;
    }

    @Test
    @DisplayName("trip(MANUAL) → 闸门熔断 + 来源含 MANUAL + 返回体带确认")
    void trip_shouldBreakGateAndRecordSource() {
        DispatchGate resp = controller.intervene(
                new InterveneRequest("trip", null, GateSource.MANUAL));

        assertTrue(resp.isBroken());
        assertEquals(GateStatus.BROKEN, resp.gateStatus());
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.MANUAL, resp.gateSources().get(0));
        assertTrue(service.summary().isBroken());
        assertTrue(resp.confirmed());
        assertEquals("", resp.message());
        assertNull(resp.orderId());
    }

    @Test
    @DisplayName("clear(MANUAL) → 仅剩 AUTO 时闸门仍为熔断中（人力不覆盖自动）")
    void clear_shouldNotReopenWhenOtherSourceRemains() {
        service.trip(GateSource.AUTO);
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(
                new InterveneRequest("clear", null, GateSource.MANUAL));

        assertTrue(resp.isBroken(), "AUTO 仍在，闸门不得恢复");
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.AUTO, resp.gateSources().get(0));
    }

    @Test
    @DisplayName("reset → 只解除人工冻结，保留 AUTO 自动熔断（HIGH.12）")
    void reset_shouldKeepAutoSource() {
        service.trip(GateSource.AUTO);
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(
                new InterveneRequest("reset", null, null));

        assertTrue(resp.isBroken(), "气象自动熔断不得被人工一键复位抹掉");
        assertEquals(GateStatus.BROKEN, resp.gateStatus());
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.AUTO, resp.gateSources().get(0));
    }

    @Test
    @DisplayName("reset 只解除人工冻结：无 AUTO 时恢复放行")
    void reset_shouldReopenWhenOnlyManualFreeze() {
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(
                new InterveneRequest("reset", null, GateSource.MANUAL));

        assertFalse(resp.isBroken());
        assertEquals(GateStatus.NORMAL, resp.gateStatus());
        assertTrue(resp.gateSources().isEmpty());
    }

    @Test
    @DisplayName("trip 缺 source → 默认 MANUAL（对齐前端不带 source 的调用）")
    void tripWithoutSource_shouldDefaultToManual() {
        DispatchGate resp = controller.intervene(
                new InterveneRequest("trip", null, null));

        assertEquals(List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("trip(AUTO) → 400：人工干预不得伪造气象自动熔断（HIGH.1）")
    void tripAuto_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(
                        new InterveneRequest("trip", null, GateSource.AUTO)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("人工干预接口只接受人工冻结来源"));
        assertFalse(service.summary().isBroken(), "被拒绝的请求不得改动闸门");
    }

    @Test
    @DisplayName("中文标签「冻结派单」→ trip(MANUAL)，前端真实调用可生效（MEDIUM.22）")
    void chineseTripLabel_shouldMapToManualTrip() {
        DispatchGate resp = controller.intervene(
                new InterveneRequest("冻结派单", null, null));

        assertTrue(resp.isBroken());
        assertEquals(List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("动作词 trim + Locale.ROOT 归一：「 TRIP 」可识别（MEDIUM.21）")
    void action_shouldBeTrimmedAndLocaleInsensitive() {
        DispatchGate resp = controller.intervene(
                new InterveneRequest(" TRIP ", null, null));

        assertTrue(resp.isBroken());
        assertEquals(List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("缺 action → 400（HIGH.13）")
    void missingAction_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(new InterveneRequest(null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("action"));
    }

    @Test
    @DisplayName("未知 action → 400，且列全八个支持动作（《1112》§3.3）")
    void unknownAction_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(new InterveneRequest("foobar", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("foobar"));
        for (String supported : InterventionAction.SUPPORTED.split("/")) {
            assertTrue(thrown.getReason().contains(supported), "支持列表缺 " + supported);
        }
    }

    @Test
    @DisplayName("五个前端中文按钮各自归一到订单动作，返回体带 confirmed/message/orderId")
    void fiveOrderButtons_shouldDispatchAndConfirm() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("调整会面点", "adjust_meet");
        cases.put("撤回未起飞订单", "withdraw");
        cases.put("取消订单", "cancel");
        cases.put("紧急叫停", "abort");
        cases.put("人工接管", "manual");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            lastAction = null;
            lastOrderId = null;
            DispatchGate resp = controller.intervene(
                    new InterveneRequest(entry.getKey(), "ORD-1", null));

            assertEquals(entry.getValue(), lastAction, entry.getKey() + " 归一值");
            assertEquals("ORD-1", lastOrderId, entry.getKey() + " 订单号");
            assertTrue(resp.confirmed(), entry.getKey() + " confirmed");
            assertEquals("", resp.message(), entry.getKey() + " message");
            assertEquals("ORD-1", resp.orderId(), entry.getKey() + " orderId");
            assertEquals(GateStatus.NORMAL, resp.gateStatus(),
                    entry.getKey() + " 是订单动作，不得改动闸门");
        }
    }

    @Test
    @DisplayName("五个订单动作缺 orderId（null 与空串）→ 400 该操作需要订单号，且不分派")
    void orderActionWithoutOrderId_shouldRejectWith400() {
        for (String action : List.of("adjust_meet", "withdraw", "cancel", "abort", "manual")) {
            for (String missing : new String[] {null, "", "   "}) {
                ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                        () -> controller.intervene(
                                new InterveneRequest(action, missing, null)),
                        action + " orderId=" + missing);

                assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
                assertEquals("该操作需要订单号", thrown.getReason());
                assertNull(lastAction, action + " 不得分派到订单出口");
            }
        }
    }

    @Test
    @DisplayName("订单动作显式传 AUTO → 400（八个动作统一适用，不再对 reset 网开一面）")
    void autoSourceOnEveryAction_shouldRejectWith400() {
        for (String action : InterventionAction.SUPPORTED.split("/")) {
            String orderId = InterventionAction.fromNormalized(action).requiresOrderId()
                    ? "ORD-1" : null;
            ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                    () -> controller.intervene(
                            new InterveneRequest(action, orderId, GateSource.AUTO)),
                    action);

            assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
            assertTrue(thrown.getReason().contains("人工干预接口只接受人工冻结来源"));
        }
    }

    @Test
    @DisplayName("「解除冻结」与按钮显示文案「解除人工冻结」均归一到 clear")
    void chineseClearLabels_shouldBothMapToClear() {
        service.trip(GateSource.MANUAL);
        DispatchGate first = controller.intervene(
                new InterveneRequest("解除冻结", null, null));
        assertFalse(first.isBroken());

        service.trip(GateSource.MANUAL);
        DispatchGate second = controller.intervene(
                new InterveneRequest("解除人工冻结", null, null));
        assertFalse(second.isBroken());
    }

    @Test
    @DisplayName("闸门级动作可空 orderId，且回吐的 orderId 为 null")
    void gateActions_shouldNotRequireOrderId() {
        for (String action : List.of("trip", "clear", "reset")) {
            DispatchGate resp = controller.intervene(
                    new InterveneRequest(action, null, null));
            assertNull(resp.orderId(), action);
            assertTrue(resp.confirmed(), action);
        }
    }

    @Test
    @DisplayName("404 订单不存在：控制器不吞异常，直接透传实现方状态码与原因")
    void notFoundFromIntervention_shouldPropagate() {
        service = new DispatchGateService((action, orderId) -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在：ORD-X");
        });
        controller = new GateController(service);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(
                        new InterveneRequest("withdraw", "ORD-X", null)));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        assertEquals("订单不存在：ORD-X", thrown.getReason());
    }

    @Test
    @DisplayName("409 状态不允许：控制器不吞异常，原因带中文状态词")
    void conflictFromIntervention_shouldPropagate() {
        service = new DispatchGateService((action, orderId) -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单当前状态不允许该操作：去程中");
        });
        controller = new GateController(service);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(
                        new InterveneRequest("withdraw", "ORD-1", null)));

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("去程中"));
    }
}
