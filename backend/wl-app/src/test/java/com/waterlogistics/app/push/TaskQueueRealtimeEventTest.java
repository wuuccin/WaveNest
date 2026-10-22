package com.waterlogistics.app.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.RendezvousFrame;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskQueueRealtimeEventTest {

    @Test
    void dispatch_shouldPublishCommittedStatusAndRendezvousProjection() {
        OrderTaskMapper orders = mock(OrderTaskMapper.class);
        TaskLogMapper logs = mock(TaskLogMapper.class);
        DispatchGateService gate = mock(DispatchGateService.class);
        List<Object> events = new ArrayList<>();
        OrderTaskEntity order = order("ORD-1", OrderStatus.DISPATCHING);
        when(orders.selectById("ORD-1")).thenReturn(order);

        TaskQueueService service = new TaskQueueService(orders, logs, gate, events::add);
        service.dispatch("ORD-1", "UAV-01", LocalDateTime.of(2026, 10, 21, 20, 0),
                115.9401, 29.7302);

        assertEquals(OrderStatus.DISPATCHED.getValue(), order.getStatus());
        assertEquals("UAV-01", order.getDroneId());
        assertEquals(new BigDecimal("115.9401"), order.getMeetLon());
        assertEquals(new BigDecimal("29.7302"), order.getMeetLat());
        assertEquals(2, events.size());

        OrderStateChangedEvent state = assertInstanceOf(OrderStateChangedEvent.class, events.get(0));
        assertEquals(OrderStatus.DISPATCHED, state.view().orderStatus());
        assertEquals("ORD-1", state.view().orderId());
        assertEquals(2L, state.view().stateVersion());

        RendezvousPlanCommittedEvent rendezvous =
                assertInstanceOf(RendezvousPlanCommittedEvent.class, events.get(1));
        assertEquals(new RendezvousFrame("ORD-1", 115.9401, 29.7302, true), rendezvous.frame());
    }

    @Test
    void dispatchWithoutRendezvousCoordinates_shouldNotPublishInventedMeetingFrame() {
        OrderTaskMapper orders = mock(OrderTaskMapper.class);
        TaskLogMapper logs = mock(TaskLogMapper.class);
        DispatchGateService gate = mock(DispatchGateService.class);
        List<Object> events = new ArrayList<>();
        when(orders.selectById("ORD-2")).thenReturn(order("ORD-2", OrderStatus.DISPATCHING));

        TaskQueueService service = new TaskQueueService(orders, logs, gate, events::add);
        service.dispatch("ORD-2", "UAV-02", LocalDateTime.of(2026, 10, 21, 20, 0));

        assertEquals(1, events.size());
        assertInstanceOf(OrderStateChangedEvent.class, events.get(0));
    }

    private static OrderTaskEntity order(String orderId, OrderStatus status) {
        OrderTaskEntity entity = new OrderTaskEntity();
        entity.setOrderId(orderId);
        entity.setStatus(status.getValue());
        return entity;
    }
}
