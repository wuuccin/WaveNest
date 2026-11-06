package com.waterlogistics.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class DispatchOrchestratorTest {

    @Test
    void orchestrate_shouldRejectWhenOrderMissingAfterStartDispatch() {
        TaskQueueService queue = new TaskQueueService(null, null, null) {
            @Override
            public void startDispatch(String orderId) {
                // 组批成功，但随后按订单号回读时行已不存在（并发删除或调用方传错单号）
            }
        };
        OrderTaskMapper mapper = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DispatchOrchestrator orchestrator = new DispatchOrchestrator(
                queue, null, mapper, null, null, null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> orchestrator.orchestrate("ORD-MISSING"));

        assertTrue(thrown.getMessage().contains("订单不存在"),
                "须点明订单不存在，而不是在取状态时空指针：" + thrown.getMessage());
    }
}
