package com.waterlogistics.app.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskQueueRealtimeEventTest {

    /**
     * 派发落定：状态推进「调度中 → 已派发」，回填 droneId 与 meetTime，
     * 并发出携带新状态的 {@link OrderStateChangedEvent}（版本自增）。
     */
    @Test
    void dispatch_shouldCommitStatusAndBackfillDroneAndMeetTime() {
        OrderTaskMapper orders = mock(OrderTaskMapper.class);
        TaskLogMapper logs = mock(TaskLogMapper.class);
        DispatchGateService gate = mock(DispatchGateService.class);
        List<Object> events = new ArrayList<>();
        OrderTaskEntity order = order("ORD-1", OrderStatus.DISPATCHING);
        when(orders.selectById("ORD-1")).thenReturn(order);
        // HIGH.29 起状态流转走条件更新并校验影响行数，mock 需返回 1
        when(orders.update(isNull(), any())).thenReturn(1);
        LocalDateTime meetTime = LocalDateTime.of(2026, 10, 21, 20, 0);

        TaskQueueService service = new TaskQueueService(orders, logs, gate, events::add);
        service.dispatch("ORD-1", "UAV-01", meetTime);

        assertEquals(OrderStatus.DISPATCHED.getValue(), order.getStatus());
        assertEquals("UAV-01", order.getDroneId());
        assertEquals(meetTime, order.getMeetTime());
        assertEquals(1, events.size());

        OrderStateChangedEvent state = assertInstanceOf(OrderStateChangedEvent.class, events.get(0));
        assertEquals(OrderStatus.DISPATCHED, state.view().orderStatus());
        assertEquals("ORD-1", state.view().orderId());
        assertEquals(2L, state.view().stateVersion());
    }

    /**
     * T1-6（2026-10-27 口径调整）：会面帧由编排器统一发布，队列侧一律不发——
     * 队列侧 dispatch 签名没有候选数与淘汰原因，再发一帧只会产生
     * 「一条全、一条空」的重复帧。本用例钉死该口径，防回归。
     */
    @Test
    void dispatch_shouldNotPublishRendezvousFrame_framePublishedByOrchestrator() {
        OrderTaskMapper orders = mock(OrderTaskMapper.class);
        TaskLogMapper logs = mock(TaskLogMapper.class);
        DispatchGateService gate = mock(DispatchGateService.class);
        List<Object> events = new ArrayList<>();
        when(orders.selectById("ORD-2")).thenReturn(order("ORD-2", OrderStatus.DISPATCHING));
        when(orders.update(isNull(), any())).thenReturn(1);

        TaskQueueService service = new TaskQueueService(orders, logs, gate, events::add);
        service.dispatch("ORD-2", "UAV-02", LocalDateTime.of(2026, 10, 21, 20, 0));

        assertTrue(events.stream().noneMatch(e -> e instanceof RendezvousPlanCommittedEvent),
                "队列侧不得发布会面帧（会面帧由编排器统一发布）");
    }

    private static OrderTaskEntity order(String orderId, OrderStatus status) {
        OrderTaskEntity entity = new OrderTaskEntity();
        entity.setOrderId(orderId);
        entity.setStatus(status.getValue());
        return entity;
    }
}
