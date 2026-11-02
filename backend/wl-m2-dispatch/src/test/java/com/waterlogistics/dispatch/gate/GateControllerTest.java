package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * {@link GateController} 单测（D2 · 2026-10-22）——<b>干预端点 → DispatchGateService 映射</b>。
 *
 * <p>不依赖 Spring：直接 new 出被测对象与真实 {@link DispatchGateService}
 * （Service 是纯内存状态机，无须 mock）。本类只测<b>请求体 → 方法调用 → 响应</b>的映射，
 * 以及 11-02 评审修复的边界：客户端错误 400、动作词归一、来源仅 MANUAL、
 * reset 保留自动熔断（HIGH.12 / HIGH.13 / MEDIUM.21 / MEDIUM.22）。
 */
class GateControllerTest {

    private DispatchGateService service;
    private GateController controller;

    @BeforeEach
    void setUp() {
        service = new DispatchGateService();
        controller = new GateController(service);
    }

    @Test
    @DisplayName("trip(MANUAL) → 闸门熔断 + 来源含 MANUAL")
    void trip_shouldBreakGateAndRecordSource() {
        DispatchGate resp = controller.intervene(new InterveneRequest("trip", GateSource.MANUAL));

        assertTrue(resp.isBroken());
        assertEquals(GateStatus.BROKEN, resp.gateStatus());
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.MANUAL, resp.gateSources().get(0));
        assertTrue(service.summary().isBroken());
    }

    @Test
    @DisplayName("clear(MANUAL) → 仅剩 AUTO 时闸门仍为熔断中（人力不覆盖自动）")
    void clear_shouldNotReopenWhenOtherSourceRemains() {
        service.trip(GateSource.AUTO);
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(new InterveneRequest("clear", GateSource.MANUAL));

        assertTrue(resp.isBroken(), "AUTO 仍在，闸门不得恢复");
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.AUTO, resp.gateSources().get(0));
    }

    @Test
    @DisplayName("reset → 只解除人工冻结，保留 AUTO 自动熔断（HIGH.12）")
    void reset_shouldKeepAutoSource() {
        service.trip(GateSource.AUTO);
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(new InterveneRequest("reset", null));

        assertTrue(resp.isBroken(), "气象自动熔断不得被人工一键复位抹掉");
        assertEquals(GateStatus.BROKEN, resp.gateStatus());
        assertEquals(1, resp.gateSources().size());
        assertEquals(GateSource.AUTO, resp.gateSources().get(0));
    }

    @Test
    @DisplayName("reset 只解除人工冻结：无 AUTO 时恢复放行")
    void reset_shouldReopenWhenOnlyManualFreeze() {
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(new InterveneRequest("reset", GateSource.MANUAL));

        assertFalse(resp.isBroken());
        assertEquals(GateStatus.NORMAL, resp.gateStatus());
        assertTrue(resp.gateSources().isEmpty());
    }

    @Test
    @DisplayName("trip 缺 source → 默认 MANUAL（对齐前端不带 source 的调用）")
    void tripWithoutSource_shouldDefaultToManual() {
        DispatchGate resp = controller.intervene(new InterveneRequest("trip", null));

        assertEquals(java.util.List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("trip(AUTO) → 400：人工干预不得伪造气象自动熔断（HIGH.1）")
    void tripAuto_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(new InterveneRequest("trip", GateSource.AUTO)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("MANUAL"));
        assertFalse(service.summary().isBroken(), "被拒绝的请求不得改动闸门");
    }

    @Test
    @DisplayName("中文标签「冻结派单」→ trip(MANUAL)，前端真实调用可生效（MEDIUM.22）")
    void chineseTripLabel_shouldMapToManualTrip() {
        DispatchGate resp = controller.intervene(new InterveneRequest("冻结派单", null));

        assertTrue(resp.isBroken());
        assertEquals(java.util.List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("动作词 trim + Locale.ROOT 归一：「 TRIP 」可识别（MEDIUM.21）")
    void action_shouldBeTrimmedAndLocaleInsensitive() {
        DispatchGate resp = controller.intervene(new InterveneRequest(" TRIP ", null));

        assertTrue(resp.isBroken());
        assertEquals(java.util.List.of(GateSource.MANUAL), resp.gateSources());
    }

    @Test
    @DisplayName("缺 action → 400（HIGH.13）")
    void missingAction_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(new InterveneRequest(null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("action"));
    }

    @Test
    @DisplayName("未知 action → 400，且提示支持的操作（HIGH.13）")
    void unknownAction_shouldRejectWith400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.intervene(new InterveneRequest("foobar", null)));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("foobar"));
        assertTrue(thrown.getReason().contains("trip"));
        assertTrue(thrown.getReason().contains("clear"));
        assertTrue(thrown.getReason().contains("reset"));
    }
}
