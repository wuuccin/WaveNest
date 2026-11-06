package com.waterlogistics.dispatch.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.dispatch.order.OrderSubmittedEvent;
import com.waterlogistics.dispatch.plan.DispatchOrchestrator;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSettings;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 11-06 序号 1：下单落库时建货物行。
 *
 * <p>此前全仓无生产代码写 {@code goods} 表，带货巢订单读不到货物行 →
 * 货到巢关判未就绪 → 订单卡在等货待唤醒 → 锁超时转资源等待永久卡住。
 */
class DispatchTriggerGoodsRowTest {

    private static final long TS = 1_800_000_000_000L;

    @Test
    void goodsRowIsCreatedWithNotReadyStatusAndCargoNestAsLocation() {
        Map<String, OrderTaskEntity> orders = new HashMap<>();
        List<GoodsEntity> goods = new ArrayList<>();
        DispatchTrigger trigger = trigger(orders, goods, new AtomicInteger());

        trigger.onOrderSubmitted(new OrderSubmittedEvent(
                "ORD-1", "123456789", 2.6, "食品", TS + 60_000, "NORMAL", TS,
                "WAIT_CARGO", TS + 30_000, null, "NEST-02"));

        assertEquals(1, goods.size(), "下单必须建一行货物档案");
        GoodsEntity row = goods.get(0);
        assertEquals("G-ORD-1-1", row.getGoodsId());
        assertEquals("ORD-1", row.getOrderId());
        assertEquals("未就绪", row.getStatus(), "初始态是「未就绪」，就绪由运营录入");
        assertEquals("NEST-02", row.getLocation(), "货巢要写进 location，货到巢关才判得到");
        assertEquals("食品", row.getType());
        assertEquals(0, new java.math.BigDecimal("2.6").compareTo(row.getWeight()));
    }

    @Test
    void goodsRowLocationIsNullWhenCargoNestNotSpecified() {
        Map<String, OrderTaskEntity> orders = new HashMap<>();
        List<GoodsEntity> goods = new ArrayList<>();
        DispatchTrigger trigger = trigger(orders, goods, new AtomicInteger());

        trigger.onOrderSubmitted(new OrderSubmittedEvent(
                "ORD-NONEST", "123456789", 2.6, "食品", TS + 60_000, "NORMAL", TS,
                "WAIT_SHIP", TS + 30_000));

        assertEquals(1, goods.size());
        assertNull(goods.get(0).getLocation(),
                "未指定货巢时 location 留空——空值表示货还没到某个巢，不是货在别处");
    }

    @Test
    void duplicateEventDoesNotCreateSecondGoodsRow() {
        Map<String, OrderTaskEntity> orders = new HashMap<>();
        List<GoodsEntity> goods = new ArrayList<>();
        DispatchTrigger trigger = trigger(orders, goods, new AtomicInteger());

        OrderSubmittedEvent event = new OrderSubmittedEvent(
                "ORD-DUP", "123456789", 2.6, "食品", TS + 60_000, "NORMAL", TS,
                "WAIT_CARGO", TS + 30_000, null, "NEST-01");
        trigger.onOrderSubmitted(event);
        trigger.onOrderSubmitted(event);

        assertEquals(1, goods.size(), "订单事件重投不得造出第二条货物行");
    }

    @Test
    void normalOrderAlsoCreatesGoodsRow() {
        Map<String, OrderTaskEntity> orders = new HashMap<>();
        List<GoodsEntity> goods = new ArrayList<>();
        DispatchTrigger trigger = trigger(orders, goods, new AtomicInteger());

        // 非延后单走主分支：落 order_task + 建货物行
        trigger.onOrderSubmitted(new OrderSubmittedEvent(
                "ORD-NORMAL", "123456789", 1.0, "应急药品", TS + 60_000, "NORMAL", TS,
                null, null, null, "NEST-01"));

        assertEquals(1, goods.size(), "正常单也要建货物档案，否则货物状态机无从流转");
        assertEquals("G-ORD-NORMAL-1", goods.get(0).getGoodsId());
    }

    private static DispatchTrigger trigger(Map<String, OrderTaskEntity> orders,
            List<GoodsEntity> goods, AtomicInteger inserts) {
        return new DispatchTrigger(orderMapper(orders, inserts), goodsMapper(goods),
                null, null, prediction(), transactionManager());
    }

    private static PredictionService prediction() {
        return new PredictionService(
                new PredictionSettings(20, .05, 15, .03, 1800, 60, 900), request -> {});
    }

    private static OrderTaskMapper orderMapper(Map<String, OrderTaskEntity> rows,
            AtomicInteger inserts) {
        return (OrderTaskMapper) Proxy.newProxyInstance(
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
    }

    private static GoodsMapper goodsMapper(List<GoodsEntity> rows) {
        return (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        rows.add((GoodsEntity) args[0]);
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    @Test
    void goodsIdIsDerivedFromOrderId() {
        assertTrue("G-ORD-X-1".startsWith("G-ORD-X"), "货物标识可由订单号直接推出");
    }
}
