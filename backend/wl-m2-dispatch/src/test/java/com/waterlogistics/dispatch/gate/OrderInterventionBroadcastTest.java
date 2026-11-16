package com.waterlogistics.dispatch.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.order.OrderStateChangedEvent;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.rendezvous.ManualMeetRecomputePort;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 人工干预的状态流转后效应单测（M2 · 序号 3）。
 *
 * <p>覆盖 11-16 序号 3-4 自验发现的两处缺口：干预只写库不广播时，订单列表与
 * {@code order_state} 推送都停在干预前；终止态只释放占位不回收机位时，无人机停在
 * 「已预占」，下一单少一架可用机。
 *
 * <p>不起 Spring、不引 mock 框架（wl-m2 的测试依赖只有 JUnit）：表用动态代理替身，
 * 事务模板用 no-op {@link PlatformTransactionManager} 包一层，占位服务传 {@code null}
 * 验证其可空口径。
 */
class OrderInterventionBroadcastTest {

    private static final String ORDER_ID = "ORD-BC-1";
    private static final String DRONE_ID = "UAV-01";
    private static final long LOG_ID = 41L;

    private final List<TaskLogEntity> auditRows = new ArrayList<>();
    private final List<Object> published = new ArrayList<>();
    private OrderTaskEntity orderRow;
    private DroneEntity droneRow;
    private int droneWrites;

    @BeforeEach
    void setUp() {
        auditRows.clear();
        published.clear();
        droneWrites = 0;
        orderRow = new OrderTaskEntity();
        orderRow.setOrderId(ORDER_ID);
        orderRow.setDroneId(DRONE_ID);
        orderRow.setStatus(OrderStatus.OUTBOUND.getValue());
        orderRow.setCargoNestId("NEST-01");
        orderRow.setGoodsType("应急药品");
        droneRow = new DroneEntity();
        droneRow.setDroneId(DRONE_ID);
        droneRow.setStatus("执行任务中");
        droneRow.setPositionType("空中");
        droneRow.setHomeNestId("NEST-01");
        droneRow.setCurrentNestId(null);
    }

    @Test
    @DisplayName("紧急叫停 → 状态落异常终止、广播该状态、机位回收为空闲回本巢")
    void abort_shouldBroadcastAndReleaseDrone() {
        String rejection = service().execute("abort", ORDER_ID);

        assertEquals(null, rejection);
        assertEquals(OrderStatus.ABORTED.getValue(), orderRow.getStatus());
        assertEquals("空闲", droneRow.getStatus(), "终止态必须把机位放回空闲，否则下一单少一架可用机");
        assertEquals("原巢", droneRow.getPositionType());
        assertEquals("NEST-01", droneRow.getCurrentNestId());
        assertEquals(1, droneWrites);
        assertEquals(1, published.size());

        OrderStateChangedEvent event = assertInstanceOf(OrderStateChangedEvent.class, published.get(0));
        assertEquals(ORDER_ID, event.view().orderId());
        assertEquals(OrderStatus.ABORTED, event.view().orderStatus(),
                "广播的必须是干预后的状态，不能是干预前的");
        assertEquals(LOG_ID + 1, event.view().stateVersion(),
                "版本号沿用 task_log.log_id + 1，前端按版本单调递增丢弃乱序帧");
        assertEquals("NEST-01", event.view().cargoNestId(), "货巢必须带上，否则会把建单货巢抹空");
        assertEquals("应急药品", event.view().goodsType(), "配送品类必须带上，否则前端整对象替换后会把已展示的品类抹成「未记录」");
    }

    @Test
    @DisplayName("机位已空闲 → 不重复回写")
    void releaseDrone_whenAlreadyIdle_shouldSkip() {
        droneRow.setStatus("空闲");

        service().execute("abort", ORDER_ID);

        assertEquals(0, droneWrites, "空闲机不重复写，避免无谓更新");
        assertEquals(1, published.size(), "广播与机位回写是两件事，前者不受后者跳过影响");
    }

    @Test
    @DisplayName("撤回未起飞订单 → 同样广播，状态落已拒绝")
    void withdraw_shouldBroadcastRejected() {
        orderRow.setStatus(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());

        service().execute("withdraw", ORDER_ID);

        assertEquals(OrderStatus.REJECTED.getValue(), orderRow.getStatus());
        OrderStateChangedEvent event = assertInstanceOf(OrderStateChangedEvent.class, published.get(0));
        assertEquals(OrderStatus.REJECTED, event.view().orderStatus());
        assertEquals(1, auditRows.size());
        assertTrue(auditRows.get(0).getOpType().startsWith("人工干预-"));
    }

    private OrderInterventionServiceImpl service() {
        return new OrderInterventionServiceImpl(orderTaskMapper(), taskLogMapper(), null,
                provider(), transactionTemplate(), droneMapper(), publisher());
    }

    private static TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // no-op：单测不落库，只验证调用序列
            }

            @Override
            public void rollback(TransactionStatus status) {
                // no-op：本类不含回滚用例
            }
        };
        return new TransactionTemplate(manager);
    }

    private OrderTaskMapper orderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return orderRow;
                    }
                    if ("updateById".equals(method.getName())) {
                        return 1;
                    }
                    return null;
                });
    }

    private TaskLogMapper taskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        TaskLogEntity entry = (TaskLogEntity) args[0];
                        entry.setLogId(LOG_ID);
                        auditRows.add(entry);
                        return 1;
                    }
                    return null;
                });
    }

    private DroneMapper droneMapper() {
        return (DroneMapper) Proxy.newProxyInstance(
                DroneMapper.class.getClassLoader(), new Class<?>[]{DroneMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return droneRow;
                    }
                    if ("updateById".equals(method.getName())) {
                        droneWrites++;
                        return 1;
                    }
                    return null;
                });
    }

    private ApplicationEventPublisher publisher() {
        return (ApplicationEventPublisher) Proxy.newProxyInstance(
                ApplicationEventPublisher.class.getClassLoader(),
                new Class<?>[]{ApplicationEventPublisher.class},
                (proxy, method, args) -> {
                    if ("publishEvent".equals(method.getName())) {
                        published.add(args[0]);
                        return null;
                    }
                    return null;
                });
    }

    private static ObjectProvider<ManualMeetRecomputePort> provider() {
        return new ObjectProvider<>() {
            @Override
            public ManualMeetRecomputePort getObject() {
                return null;
            }

            @Override
            public ManualMeetRecomputePort getObject(Object... args) {
                return null;
            }

            @Override
            public ManualMeetRecomputePort getIfAvailable() {
                return null;
            }

            @Override
            public ManualMeetRecomputePort getIfUnique() {
                return null;
            }
        };
    }
}
