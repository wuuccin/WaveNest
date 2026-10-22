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

/**
 * {@link GateController} 单测（D2 · 2026-10-22）——<b>干预端点 → DispatchGateService 映射</b>。
 *
 * <p>不依赖 Spring：直接 new 出被测对象与真实 {@link DispatchGateService}
 * （Service 是纯内存状态机，无须 mock）。Service 层的语义正确性
 * （trip/clear/reset 的状态变迁）已由 P0-6 与 B3 的 Smoke 级测试覆盖，
 * 本类只测<b>请求体 → 方法调用 → 响应</b>的三段映射是否对齐。
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
    @DisplayName("reset → 一键复位，忽略 source 字段")
    void reset_shouldClearAllSources() {
        service.trip(GateSource.AUTO);
        service.trip(GateSource.MANUAL);

        DispatchGate resp = controller.intervene(new InterveneRequest("reset", null));

        assertFalse(resp.isBroken());
        assertEquals(GateStatus.NORMAL, resp.gateStatus());
        assertTrue(resp.gateSources().isEmpty());
    }

    @Test
    @DisplayName("trip 缺 source → IllegalArgumentException（400 语义）")
    void tripWithoutSource_shouldReject() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> controller.intervene(new InterveneRequest("trip", null)));

        assertTrue(thrown.getMessage().contains("trip"));
    }

    @Test
    @DisplayName("clear 缺 source → IllegalArgumentException")
    void clearWithoutSource_shouldReject() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.intervene(new InterveneRequest("clear", null)));
    }

    @Test
    @DisplayName("未知 action → IllegalArgumentException")
    void unknownAction_shouldReject() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> controller.intervene(new InterveneRequest("foobar", null)));

        assertTrue(thrown.getMessage().contains("foobar"));
        assertTrue(thrown.getMessage().contains("trip"));
        assertTrue(thrown.getMessage().contains("clear"));
        assertTrue(thrown.getMessage().contains("reset"));
    }
}
