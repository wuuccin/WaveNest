package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link InterventionAction} 单测：八个动作的归一映射、状态准入与目标状态。
 *
 * <p>纯函数、不依赖 Spring 与数据库，直接钉住《1112-功能做实批次｜接口约定》§3.2
 * 与《23-状态机定义》§3 的判定，避免状态准入只在集成链路里被隐式覆盖。
 */
class InterventionActionTest {

    @Test
    @DisplayName("八个中文按钮原文与别名全部归一到英文 action")
    void normalize_shouldMapEveryChineseLabel() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("冻结派单", "trip");
        cases.put("冻结", "trip");
        cases.put("解除冻结", "clear");
        cases.put("解冻", "clear");
        cases.put("解除人工冻结", "clear");
        cases.put("重置", "reset");
        cases.put("复位", "reset");
        cases.put("调整会面点", "adjust_meet");
        cases.put("撤回未起飞订单", "withdraw");
        cases.put("取消订单", "cancel");
        cases.put("紧急叫停", "abort");
        cases.put("人工接管", "manual");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            assertEquals(entry.getValue(), InterventionAction.normalize(entry.getKey()),
                    entry.getKey());
        }
        assertEquals("trip", InterventionAction.normalize(" TRIP "));
        assertEquals("unknown", InterventionAction.normalize(" UnKnown "));
        assertEquals("foobar", InterventionAction.normalize("foobar"));
    }

    @Test
    @DisplayName("支持列表与八个枚举取值逐一对应，且仅五个订单动作必填 orderId")
    void supportedList_shouldMatchEnumValues() {
        String[] listed = InterventionAction.SUPPORTED.split("/");
        assertEquals(InterventionAction.values().length, listed.length);
        for (int i = 0; i < listed.length; i++) {
            InterventionAction action = InterventionAction.fromNormalized(listed[i]);
            assertEquals(InterventionAction.values()[i].action(), action.action());
        }

        List<String> orderActions = List.of("adjust_meet", "withdraw", "cancel", "abort", "manual");
        for (InterventionAction action : InterventionAction.values()) {
            assertEquals(orderActions.contains(action.action()), action.requiresOrderId(),
                    action.action());
        }
    }

    @Test
    @DisplayName("withdraw 仅 OUTBOUND 之前五态可执行（§3.3 的 409 例子）")
    void withdraw_shouldRejectFromOutboundOnwards() {
        InterventionAction withdraw = InterventionAction.fromNormalized("withdraw");

        for (OrderStatus status : List.of(OrderStatus.PENDING_ACCEPT,
                OrderStatus.ACCEPTED_WAIT_WAKE, OrderStatus.WAIT_DISPATCH,
                OrderStatus.DISPATCHING, OrderStatus.DISPATCHED)) {
            assertTrue(withdraw.allows(status), status.getValue());
        }
        for (OrderStatus status : List.of(OrderStatus.OUTBOUND, OrderStatus.HANDOVER,
                OrderStatus.RETURNING)) {
            assertFalse(withdraw.allows(status), status.getValue());
        }
        assertEquals(OrderStatus.REJECTED, withdraw.targetStatus());
    }

    @Test
    @DisplayName("abort 仅去程中／末段交接中／返航中可执行，落异常终止")
    void abort_shouldOnlyAllowInflight() {
        InterventionAction abort = InterventionAction.fromNormalized("abort");

        for (OrderStatus status : List.of(OrderStatus.OUTBOUND, OrderStatus.HANDOVER,
                OrderStatus.RETURNING)) {
            assertTrue(abort.allows(status), status.getValue());
        }
        for (OrderStatus status : List.of(OrderStatus.PENDING_ACCEPT,
                OrderStatus.WAIT_DISPATCH, OrderStatus.DISPATCHED, OrderStatus.FINISHED)) {
            assertFalse(abort.allows(status), status.getValue());
        }
        assertEquals(OrderStatus.ABORTED, abort.targetStatus());
    }

    @Test
    @DisplayName("三个终止态与已完成：所有订单动作一律拒绝（§3 约束 3）")
    void terminalAndFinished_shouldRejectEveryOrderAction() {
        for (String name : List.of("adjust_meet", "withdraw", "cancel", "abort", "manual")) {
            InterventionAction action = InterventionAction.fromNormalized(name);
            for (OrderStatus status : List.of(OrderStatus.REJECTED, OrderStatus.FAILED,
                    OrderStatus.ABORTED, OrderStatus.FINISHED)) {
                assertFalse(action.allows(status), name + " @ " + status.getValue());
            }
        }
    }

    @Test
    @DisplayName("cancel/manual 覆盖全部在途非终止态；manual 按《23》§3 落已拒绝（转人工）")
    void cancelAndManual_shouldCoverAllActiveStates() {
        for (String name : List.of("cancel", "manual")) {
            InterventionAction action = InterventionAction.fromNormalized(name);
            for (OrderStatus status : List.of(OrderStatus.PENDING_ACCEPT,
                    OrderStatus.ACCEPTED_WAIT_WAKE, OrderStatus.WAIT_DISPATCH,
                    OrderStatus.DISPATCHING, OrderStatus.DISPATCHED,
                    OrderStatus.OUTBOUND, OrderStatus.HANDOVER, OrderStatus.RETURNING)) {
                assertTrue(action.allows(status), name + " @ " + status.getValue());
            }
            assertEquals(OrderStatus.REJECTED, action.targetStatus());
        }
    }

    @Test
    @DisplayName("adjust_meet 不改订单状态：目标态为 null，重算由 ManualMeetRecomputePort 承接")
    void adjustMeet_shouldKeepStatus() {
        InterventionAction adjust = InterventionAction.fromNormalized("adjust_meet");

        assertNull(adjust.targetStatus());
        assertTrue(adjust.requiresOrderId());
        assertTrue(adjust.allows(OrderStatus.DISPATCHED));
        assertFalse(adjust.allows(OrderStatus.FINISHED));
    }

    @Test
    @DisplayName("三个闸门级动作不看订单状态、不要求订单号、不改订单状态")
    void gateActions_shouldIgnoreOrderState() {
        for (String name : List.of("trip", "clear", "reset")) {
            InterventionAction action = InterventionAction.fromNormalized(name);
            assertFalse(action.requiresOrderId(), name);
            assertNull(action.targetStatus(), name);
            assertTrue(action.allows(OrderStatus.FINISHED), name);
            assertTrue(action.allows(OrderStatus.ABORTED), name);
        }
    }
}
