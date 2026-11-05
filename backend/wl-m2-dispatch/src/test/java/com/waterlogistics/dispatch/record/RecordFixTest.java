package com.waterlogistics.dispatch.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordFixTest {
    @Test
    void recorderRejectsMissingFieldsAndBoundsOrders() {
        TaskLogRecorder recorder = new TaskLogRecorder();
        // 空订单号：留痕不阻断调度主流程——记 warn 后丢弃，既不抛也不留痕
        recorder.record(null, "op", null, "state", 1);
        recorder.record("  ", "op", null, "state", 1);
        assertEquals(0, recorder.trackedOrderCount(), "空订单号的事件流不得留痕");
        assertThrows(IllegalArgumentException.class, () -> recorder.record("id", " ", null, "state", 1));
        assertThrows(IllegalArgumentException.class, () -> recorder.record("id", "op", null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> recorder.entriesOf(null));
        for (int i = 0; i < 1_005; i++) recorder.record("order-" + i, "op", null, "state", i);
        assertEquals(1_000, recorder.trackedOrderCount());
        assertEquals(List.of(), recorder.entriesOf("order-0"));
        assertEquals(1, recorder.entriesOf("order-1004").size());
    }

    @Test
    void boundedPageIsPassedToMapper() {
        AtomicReference<String> sql = new AtomicReference<>();
        OrderTaskMapper orders = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("selectList")) {
                        sql.set(((QueryWrapper<?>) args[0]).getSqlSegment());
                        return List.of(new OrderTaskEntity());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        MonitorQueryService service = new MonitorQueryService(orders, null, null);
        assertEquals(1, service.tasks(2, 500).total());
        assertTrue(sql.get().contains("200"), sql.get());
    }

    @Test
    void sqlAggregatePreservesAllStatusBuckets() {
        LocalDateTime last = LocalDateTime.of(2026, 11, 1, 9, 0);
        var result = MonitorAggregator.toStatistics(Map.of(
                OrderStatus.DISPATCHED.getValue(), 2L,
                OrderStatus.RETURNING.getValue(), 3L,
                "bad-status", 1L), 8L, last);
        assertEquals(6, result.totalOrders());
        assertEquals(2, result.dispatchedCount());
        assertEquals(3, result.inFlightCount());
        assertEquals(8, result.taskLogCount());
        assertEquals(last, result.lastOrderAt());
        assertEquals(1, result.byStatus().stream().filter(s -> s.code().equals("UNKNOWN"))
                .findFirst().orElseThrow().count());
    }
}
