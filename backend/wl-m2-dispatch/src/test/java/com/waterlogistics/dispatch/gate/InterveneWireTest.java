package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.rest.InterveneRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * {@code /api/intervene} 的 JSON 线格式单测（Jackson 3 · 序号 3）。
 *
 * <p>钉住《1112-功能做实批次｜接口约定》§3.1／§3.4／§3.5 的真实前端载荷：
 * {@code App.vue:370} 只发 {@code {action, orderId}}、不带 {@code source}，
 * 三参 record 必须能反序列化且 {@code source} 为 {@code null}（由控制器兜成 MANUAL）；
 * 响应体必须带 {@code confirmed}／{@code message}／{@code orderId} 三个新增字段。
 *
 * <p>不依赖 Spring MVC：请求体与响应体都用生产同款 {@link JsonMapper} 直接往返，
 * 避免「单元测试全绿、真实 HTTP 因缺字段 400」。
 */
class InterveneWireTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("前端只发 {action, orderId}：三参 record 可反序列化，source 为 null")
    void frontEndPayload_shouldDeserializeWithoutSource() {
        InterveneRequest request = mapper.readValue(
                "{\"action\":\"撤回未起飞订单\",\"orderId\":\"ORD-1\"}",
                InterveneRequest.class);

        assertEquals("撤回未起飞订单", request.action());
        assertEquals("ORD-1", request.orderId());
        assertNull(request.source(), "前端不传 source，控制器须兜成 MANUAL");
    }

    @Test
    @DisplayName("只发 {action, source}：orderId 为 null，闸门级动作可用")
    void gatePayload_shouldDeserializeWithoutOrderId() {
        InterveneRequest request = mapper.readValue(
                "{\"action\":\"trip\",\"source\":\"MANUAL\"}",
                InterveneRequest.class);

        assertEquals("trip", request.action());
        assertNull(request.orderId());
        assertEquals(GateSource.MANUAL, request.source());
    }

    @Test
    @DisplayName("DispatchGate 序列化含 §3.4 五个字段，确认态三个新增字段取值正确")
    void dispatchGate_shouldExposeAllFiveFields() {
        DispatchGate confirmed = new DispatchGate(
                GateStatus.BROKEN, List.of(GateSource.MANUAL)).withConfirmation("ORD-1");
        JsonNode body = mapper.readTree(mapper.writeValueAsString(confirmed));

        // §3.4 只列五个字段；isBroken() 已 @JsonIgnore，不再多出 "broken"。
        assertEquals(5, body.size());
        assertEquals("熔断中", body.get("gateStatus").asText(), "§3.4：写中文取值，不写枚举名");
        assertEquals("人工冻结", body.get("gateSources").get(0).asText(), "§3.4：写中文取值");
        assertTrue(body.get("confirmed").asBoolean());
        assertEquals("", body.get("message").asText());
        assertEquals("ORD-1", body.get("orderId").asText());
    }

    @Test
    @DisplayName("GET /api/orders 的两参摘要：不带确认语义，orderId 为 null")
    void plainSummary_shouldNotClaimConfirmation() {
        JsonNode body = mapper.readTree(mapper.writeValueAsString(
                new DispatchGate(GateStatus.NORMAL, List.of())));

        assertEquals(5, body.size());
        assertEquals("正常", body.get("gateStatus").asText());
        assertEquals(0, body.get("gateSources").size(), "正常时来源为空数组");
        assertFalse(body.get("confirmed").asBoolean());
        assertEquals("", body.get("message").asText());
        assertTrue(body.get("orderId").isNull());
    }

    @Test
    @DisplayName("未确认结果：confirmed=false 且 message 带具体原因")
    void rejection_shouldCarryReason() {
        JsonNode body = mapper.readTree(mapper.writeValueAsString(
                new DispatchGate(GateStatus.NORMAL, List.of())
                        .withRejection("该操作需要订单号", null)));

        assertFalse(body.get("confirmed").asBoolean());
        assertEquals("该操作需要订单号", body.get("message").asText());
        assertTrue(body.get("orderId").isNull());
    }
}
