package com.waterlogistics.dispatch.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderSubmittedEvent;
import com.waterlogistics.dispatch.plan.DispatchOrchestrator;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSettings;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DispatchTriggerFixTest {
    private static final long TS = 1_800_000_000_000L;

    @Test
    void duplicateEventIsInsertedOnceAndUsesShanghaiTime() {
        Map<String, OrderTaskEntity> rows = new HashMap<>();
        AtomicInteger inserts = new AtomicInteger();
        OrderTaskMapper mapper = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> rows.get(args[0]);
                    case "insert" -> {
                        OrderTaskEntity row = (OrderTaskEntity) args[0];
                        rows.put(row.getOrderId(), row);
                        inserts.incrementAndGet();
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PredictionService prediction = new PredictionService(
                new PredictionSettings(20, .05, 15, .03, 1800, 60, 900), request -> {});
        DispatchTrigger trigger = new DispatchTrigger(mapper, null, null, prediction, transactionManager());
        OrderSubmittedEvent event = new OrderSubmittedEvent(
                "ORD-1", "123456789", 5.0, "medicine", TS + 60_000, "NORMAL", TS,
                "WAIT_SHIP", TS + 30_000);
        trigger.onOrderSubmitted(event);
        trigger.onOrderSubmitted(event);
        assertEquals(1, inserts.get());
        assertEquals(OrderStatus.ACCEPTED_WAIT_WAKE.getValue(), rows.get("ORD-1").getStatus());
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(TS), ZoneId.of("Asia/Shanghai")),
                rows.get("ORD-1").getCreateTime());
        assertNull(rows.get("ORD-1").getShipLon());
    }

    private static PlatformTransactionManager transactionManager() {
        return (PlatformTransactionManager) Proxy.newProxyInstance(
                PlatformTransactionManager.class.getClassLoader(),
                new Class<?>[]{PlatformTransactionManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTransaction" -> new SimpleTransactionStatus();
                    case "commit", "rollback" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
