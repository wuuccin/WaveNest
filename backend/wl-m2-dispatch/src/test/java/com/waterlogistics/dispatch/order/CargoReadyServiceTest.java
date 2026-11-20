package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CargoReadyService} 单测（M2 · 卢肄文 · 11-05 序号 1）。
 *
 * <p>这个端点的验收点几乎全在<b>幂等</b>上：货主重试、前端双击、MQ 重投都会重复调用，
 * 「又到货一次」必须与「上次就改好了」区分开，否则序号 2 的「货到巢关放行」会被
 * 同一条事实触发多次。故本类的主断言不是「状态变成了已就绪」（那太容易过），
 * 而是<b>第二次调用零写入</b>——直接数 {@code updateById} 的调用次数。
 *
 * <p>无库依赖：{@code GoodsMapper} 是接口，用 JDK 动态代理做一张内存表。
 */
class CargoReadyServiceTest {

    private static final String ORDER_ID = "ORD-1";

    /** 订单记的货巢。 */
    private static final String ORDER_NEST_ID = "NEST-01";

    private final List<GoodsEntity> table = new ArrayList<>();

    /** {@code updateById} 被调用的次数——幂等的直接证据。 */
    private int updateCalls;

    /** 订单表内存行的货巢；为空表示订单没货巢。 */
    private String orderNestId = ORDER_NEST_ID;

    /** 订单是否存在。 */
    private boolean orderExists = true;

    /** 订单当前状态（资源释放用例用）。 */
    private String orderStatus = OrderStatus.WAIT_DISPATCH.getValue();

    /** 订单当前等待原因（资源释放用例用）。 */
    private String orderWaitReason;

    /** {@code task_log} 写入的动作名——受控动作留痕的直接证据。 */
    private final List<String> taskLogOpTypes = new ArrayList<>();

    private CargoReadyService service;

    /** Lambda 条件更新需要 MyBatis-Plus 的 TableInfo 缓存（无 Spring 上下文时手工初始化）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "cargo-ready-test"),
                OrderTaskEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "cargo-ready-test"),
                TaskLogEntity.class);
    }

    @BeforeEach
    void setUp() {
        table.clear();
        updateCalls = 0;
        orderNestId = ORDER_NEST_ID;
        orderExists = true;
        orderStatus = OrderStatus.WAIT_DISPATCH.getValue();
        orderWaitReason = null;
        taskLogOpTypes.clear();
        OrderTaskMapper orderMapper = inMemoryOrderMapper();
        TaskQueueService taskQueueService =
                new TaskQueueService(orderMapper, inMemoryTaskLogMapper(), null);
        service = new CargoReadyService(inMemoryMapper(), orderMapper, taskQueueService);
    }

    /** {@code task_log} 桩：只记录动作名。 */
    private TaskLogMapper inMemoryTaskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        taskLogOpTypes.add(((TaskLogEntity) args[0]).getOpType());
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    /**
     * 订单表桩：{@code selectOne}/{@code selectById} 返回一行带货巢的订单，
     * 条件更新（{@code update}）恒回 1 行。
     */
    private OrderTaskMapper inMemoryOrderMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())
                            || "selectById".equals(method.getName())) {
                        if (!orderExists) {
                            return null;
                        }
                        OrderTaskEntity order = new OrderTaskEntity();
                        order.setOrderId(ORDER_ID);
                        order.setCargoNestId(orderNestId);
                        order.setStatus(orderStatus);
                        order.setWaitReason(orderWaitReason);
                        return order;
                    }
                    if ("update".equals(method.getName())) {
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    private GoodsMapper inMemoryMapper() {
        return (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.copyOf(table);
                    }
                    if ("updateById".equals(method.getName())) {
                        updateCalls++;
                        GoodsEntity incoming = (GoodsEntity) args[0];
                        for (GoodsEntity row : table) {
                            if (row.getGoodsId().equals(incoming.getGoodsId())) {
                                row.setStatus(incoming.getStatus());
                            }
                        }
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    private GoodsEntity seed(String goodsId, String status) {
        GoodsEntity row = new GoodsEntity();
        row.setGoodsId(goodsId);
        row.setOrderId(ORDER_ID);
        row.setStatus(status);
        table.add(row);
        return row;
    }

    @Test
    @DisplayName("★ 未就绪 → 已就绪：写一次库，回 changed=true")
    void notReady_shouldBecomeReady() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertTrue(response.accepted());
        assertTrue(response.changed());
        assertEquals(1, response.updatedRows());
        assertEquals(GoodsStatus.READY.getValue(), response.goodsStatus());
        assertNull(response.reasonCode(), "正常改动不带原因码");
        assertEquals(GoodsStatus.READY.getValue(), table.get(0).getStatus());
        assertEquals(1, updateCalls);
    }

    @Test
    @DisplayName("★ 幂等：重复调用零写入，回 changed=false + ALREADY_READY")
    void repeatedCall_shouldBeNoOp() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse first = service.markReady(ORDER_ID, ORDER_NEST_ID);
        int callsAfterFirst = updateCalls;
        CargoReadyResponse second = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertTrue(first.changed());
        assertFalse(second.changed(), "第二次必须是「没改」");
        assertEquals(0, second.updatedRows());
        assertEquals(CargoReadyResponse.Reason.ALREADY_READY.name(), second.reasonCode());
        assertEquals(callsAfterFirst, updateCalls,
                "幂等命中不得再打库——否则「放行」会被触发第二次");
        assertTrue(second.accepted(), "重复报告不算错误：事实仍成立");
    }

    @Test
    @DisplayName("订单号两侧空白容忍（同一笔订单不会因为空格被当成两笔）")
    void orderIdWhitespace_shouldBeTolerated() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        assertTrue(service.markReady("  " + ORDER_ID + " ", ORDER_NEST_ID).changed());
        assertFalse(service.markReady(ORDER_ID, ORDER_NEST_ID).changed(), "归一后是同一笔订单");
    }

    @Test
    @DisplayName("查无货物记录 → 拒绝，且不凭空建行")
    void missingGoods_shouldBeRejected() {
        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.GOODS_NOT_FOUND.name(), response.reasonCode());
        assertEquals(0, updateCalls);
        assertTrue(table.isEmpty(), "不得 upsert：货物记录由下单落库时建立");
    }

    @Test
    @DisplayName("订单号缺失/空白 → ORDER_ID_MISSING（不是「查无货物」）")
    void blankOrderId_shouldBeRejected() {
        assertEquals(CargoReadyResponse.Reason.ORDER_ID_MISSING.name(),
                service.markReady(null, ORDER_NEST_ID).reasonCode());
        assertEquals(CargoReadyResponse.Reason.ORDER_ID_MISSING.name(),
                service.markReady("   ", ORDER_NEST_ID).reasonCode());
    }

    @Test
    @DisplayName("★ 已装机不得回退为已就绪（状态机不许倒退）")
    void loadedGoods_shouldNotRollBack() {
        seed("G-1", GoodsStatus.LOADED.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.GOODS_STATE_FROZEN.name(), response.reasonCode());
        assertEquals(GoodsStatus.LOADED.getValue(), table.get(0).getStatus());
        assertEquals(0, updateCalls, "已装机的货不能被拽回巢里");
    }

    @Test
    @DisplayName("一单多货行：只翻未就绪的那几行，已交付的不动")
    void multipleRows_shouldOnlyFlipNotReady() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());
        seed("G-2", GoodsStatus.NOT_READY.getValue());
        seed("G-3", GoodsStatus.DELIVERED.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertTrue(response.changed());
        assertEquals(2, response.updatedRows());
        assertEquals(GoodsStatus.READY.getValue(), table.get(0).getStatus());
        assertEquals(GoodsStatus.READY.getValue(), table.get(1).getStatus());
        assertEquals(GoodsStatus.DELIVERED.getValue(), table.get(2).getStatus());
    }

    // ───────────── 货巢核对 ─────────────

    @Test
    @DisplayName("★ 请求体缺货巢编号 → CARGO_NEST_REQUIRED，且不写库")
    void missingCargoNest_shouldBeRejected() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, "   ");

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.CARGO_NEST_REQUIRED.name(), response.reasonCode());
        assertEquals(0, updateCalls, "缺巢编号不得动库");
        assertEquals(GoodsStatus.NOT_READY.getValue(), table.get(0).getStatus());
    }

    @Test
    @DisplayName("★ 订单没有货巢 → CARGO_NEST_MISSING_ON_ORDER，没有比对基准不放行")
    void orderWithoutCargoNest_shouldBeRejected() {
        orderNestId = null;
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.CARGO_NEST_MISSING_ON_ORDER.name(),
                response.reasonCode());
        assertEquals(0, updateCalls);
    }

    @Test
    @DisplayName("★ 货巢不一致 → CARGO_NEST_MISMATCH，货物状态与位置都不动")
    void mismatchedCargoNest_shouldBeRejected() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, "NEST-99");

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.CARGO_NEST_MISMATCH.name(),
                response.reasonCode());
        assertEquals(0, updateCalls, "巢不一致不得动库");
        assertEquals(GoodsStatus.NOT_READY.getValue(), table.get(0).getStatus());
    }

    @Test
    @DisplayName("订单不存在 → ORDER_NOT_FOUND")
    void missingOrder_shouldBeRejected() {
        orderExists = false;
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.ORDER_NOT_FOUND.name(), response.reasonCode());
        assertEquals(0, updateCalls);
    }

    @Test
    @DisplayName("★ 巢一致时写入货物实际到达的巢")
    void matchingCargoNest_shouldWriteLocation() {
        seed("G-1", GoodsStatus.NOT_READY.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID, ORDER_NEST_ID);

        assertTrue(response.accepted());
        assertTrue(response.changed());
        assertEquals(ORDER_NEST_ID, table.get(0).getLocation(),
                "登记后货物位置必须是实际到达的巢");
        assertEquals(GoodsStatus.READY.getValue(), table.get(0).getStatus());
    }

    // ───────────── 资源等待释放（11-19 序号 1-2） ─────────────

    @Test
    @DisplayName("★ 资源等待释放：回待调度并写 task_log「资源释放-人工」")
    void resourceWait_shouldReturnToDispatchWithLog() {
        orderStatus = OrderStatus.ACCEPTED_WAIT_WAKE.getValue();
        orderWaitReason = WaitReason.WAIT_RESOURCE.name();

        ResourceReleaseResponse response = service.releaseResourceWait("  " + ORDER_ID + " ");

        assertTrue(response.released(), "宽限期内已转资源等待的单必须可释放");
        assertEquals(ORDER_ID, response.orderId(), "订单号回显归一后的值");
        assertEquals(OrderStatus.WAIT_DISPATCH.getValue(), response.status());
        assertEquals(List.of("资源释放-人工"), taskLogOpTypes,
                "留痕动作名必须是「资源释放-人工」，与会面放弃重入队区分开");
    }

    @Test
    @DisplayName("★ 仅等待资源的单可释放：待调度的单被拒，且不写 task_log")
    void notResourceWait_shouldBeRejected() {
        orderStatus = OrderStatus.WAIT_DISPATCH.getValue();

        ResourceReleaseResponse response = service.releaseResourceWait(ORDER_ID);

        assertFalse(response.released());
        assertEquals(OrderStatus.WAIT_DISPATCH.getValue(), response.status(), "回显当前状态");
        assertTrue(response.message().contains("不满足释放条件"));
        assertTrue(taskLogOpTypes.isEmpty(), "被拒的请求不得留痕");
    }

    @Test
    @DisplayName("★ 同为已受理待唤醒但等待原因是等货：未到期，不得释放")
    void waitCargo_shouldBeRejected() {
        orderStatus = OrderStatus.ACCEPTED_WAIT_WAKE.getValue();
        orderWaitReason = WaitReason.WAIT_CARGO.name();

        ResourceReleaseResponse response = service.releaseResourceWait(ORDER_ID);

        assertFalse(response.released(), "等货未到期，放回待调度只会被门关再次拦下");
        assertTrue(taskLogOpTypes.isEmpty());
    }

    @Test
    @DisplayName("订单不存在 → 拒绝，且不抛异常")
    void releaseMissingOrder_shouldBeRejected() {
        orderExists = false;

        ResourceReleaseResponse response = service.releaseResourceWait(ORDER_ID);

        assertFalse(response.released());
        assertNull(response.status());
        assertTrue(taskLogOpTypes.isEmpty());
    }
}
