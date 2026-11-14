package com.waterlogistics.dispatch.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.goods.GoodsLifecycleService;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 落巢回收确认处理测试（11-07 弥补 D-03／D-09／D-16）。
 *
 * <p>无库依赖：两个 Mapper 用 JDK 动态代理，货物状态机用可覆写的桩类，
 * 覆盖「货物仍已装机才写已回收」「重复确认只写一次」「野外落点拒收」三条判据。
 */
class GoodsRecycleHandlerTest {

    private static final String ORDER = "ORD-1";
    private static final String GOODS = "G-ORD-1-1";
    private static final String UAV = "UAV-01";
    private static final String NEST = "NEST-01";

    /** 只覆写本次要用的三个口，避免为纯单测引入 Mockito 依赖。 */
    private static final class StubLifecycle extends GoodsLifecycleService {
        boolean loaded = true;
        boolean recycled = false;
        int recycleCalls;

        StubLifecycle() {
            super(null);
        }

        @Override
        public boolean isLoaded(String orderId, String goodsId, String droneId) {
            return loaded;
        }

        @Override
        public boolean isRecycled(String orderId, String goodsId) {
            return recycled;
        }

        @Override
        public void recycle(String orderId, String goodsId, String droneId, String nestId) {
            recycleCalls++;
        }
    }

    private final List<TaskLogEntity> logs = new ArrayList<>();
    private final StubLifecycle lifecycle = new StubLifecycle();
    /** 记录 {@code requeue} 调用（回收后订单回待调度的唯一动作）。 */
    private final List<String> requeued = new ArrayList<>();
    private boolean orderExists = true;
    private String orderStatus = OrderStatus.RETURNING.getValue();

    private GoodsRecycleHandler handler() {
        OrderTaskMapper orderMapper = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(),
                new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        if (!orderExists) {
                            return null;
                        }
                        OrderTaskEntity entity = new OrderTaskEntity();
                        entity.setOrderId(ORDER);
                        entity.setStatus(orderStatus);
                        return entity;
                    }
                    if ("toString".equals(method.getName())) {
                        return "stubOrderTaskMapper";
                    }
                    return null;
                });
        TaskLogMapper logMapper = (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(),
                new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        logs.add((TaskLogEntity) args[0]);
                        return 1;
                    }
                    if ("toString".equals(method.getName())) {
                        return "stubTaskLogMapper";
                    }
                    return null;
                });
        return new GoodsRecycleHandler(lifecycle, orderMapper, logMapper, queueService());
    }

    /** 只验证「是否发起重入队」，状态迁移本身由 {@code TaskQueueService} 自己的用例覆盖。 */
    private TaskQueueService queueService() {
        return new TaskQueueService(null, null, null) {
            @Override
            public void requeue(String orderId) {
                requeued.add(orderId);
            }
        };
    }

    private static GoodsRecycleConfirmation message(String nestId) {
        return new GoodsRecycleConfirmation(ORDER, GOODS, UAV, nestId, System.currentTimeMillis());
    }

    @Test
    @DisplayName("货物仍「已装机」：写「已回收」并记一条货物状态 task_log")
    void confirm_shouldWriteRecycledWhenGoodsStillLoaded() {
        assertEquals(GoodsRecycleHandler.OK, handler().confirm(message(NEST)));
        assertEquals(1, lifecycle.recycleCalls);
        assertEquals(1, logs.size());
        assertEquals(ORDER, logs.get(0).getOrderId());
        assertEquals(GoodsStatus.LOADED.getValue(), logs.get(0).getFromStatus());
        assertEquals(GoodsStatus.RECYCLED.getValue(), logs.get(0).getToStatus());
        assertTrue(logs.get(0).getOpType().contains("回收确认"));
    }

    @Test
    @DisplayName("重复确认：货物已「已回收」时不二次写货物行与 task_log")
    void confirm_shouldBeIdempotentWhenAlreadyRecycled() {
        lifecycle.loaded = false;
        lifecycle.recycled = true;

        assertEquals(GoodsRecycleHandler.ERR_DUPLICATE, handler().confirm(message(NEST)));
        assertEquals(0, lifecycle.recycleCalls);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("货物不在「已装机」且未回收：拒绝，不写任何库表")
    void confirm_shouldRejectWhenGoodsNotLoaded() {
        lifecycle.loaded = false;

        assertEquals(GoodsRecycleHandler.ERR_GOODS_NOT_LOADED, handler().confirm(message(NEST)));
        assertEquals(0, lifecycle.recycleCalls);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("野外迫降点不得确认回收：落点标识不匹配")
    void confirm_shouldRejectFieldLanding() {
        assertEquals(GoodsRecycleHandler.ERR_LANDING_POINT_MISMATCH,
                handler().confirm(message("FIELD-1")));
        assertEquals(0, lifecycle.recycleCalls);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("订单号不存在：拒绝，不写任何库表")
    void confirm_shouldRejectWhenOrderMissing() {
        orderExists = false;

        assertEquals(GoodsRecycleHandler.ERR_ORDER_NOT_FOUND, handler().confirm(message(NEST)));
        assertEquals(0, lifecycle.recycleCalls);
        assertTrue(logs.isEmpty());
    }

    @Test
    @DisplayName("中止返航后回收：订单回待调度，重派闭环走得通")
    void confirm_shouldRequeueOrderAfterRecycle() {
        orderStatus = OrderStatus.RETURNING.getValue();

        assertEquals(GoodsRecycleHandler.OK, handler().confirm(message(NEST)));
        assertEquals(List.of(ORDER), requeued, "回收确认后必须重入队，否则重派前置永远不过");
    }

    @Test
    @DisplayName("正常交付后回收（已完成）：不动订单，不回待调度")
    void confirm_shouldNotRequeueFinishedOrder() {
        orderStatus = OrderStatus.FINISHED.getValue();

        assertEquals(GoodsRecycleHandler.OK, handler().confirm(message(NEST)));
        assertTrue(requeued.isEmpty(), "已完成单子不因回收回到待调度");
    }

    @Test
    @DisplayName("幂等键由订单／货物／执行机／落点四字段组成，落点不同即不同事件")
    void idempotencyKey_shouldCoverFourFields() {
        assertEquals(message(NEST).idempotencyKey(), message(NEST).idempotencyKey());
        assertNotEquals(message(NEST).idempotencyKey(), message("NEST-02").idempotencyKey());
    }

    @Test
    @DisplayName("四个必填字段任一为空都拒收：野外迫降不得伪装成落巢回收")
    void confirmation_shouldRejectBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new GoodsRecycleConfirmation(" ", GOODS, UAV, NEST, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new GoodsRecycleConfirmation(ORDER, null, UAV, NEST, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new GoodsRecycleConfirmation(ORDER, GOODS, "", NEST, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new GoodsRecycleConfirmation(ORDER, GOODS, UAV, "", 1L));
    }
}
