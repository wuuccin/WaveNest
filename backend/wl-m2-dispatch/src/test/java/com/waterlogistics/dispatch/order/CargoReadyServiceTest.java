package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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

    private final List<GoodsEntity> table = new ArrayList<>();

    /** {@code updateById} 被调用的次数——幂等的直接证据。 */
    private int updateCalls;

    private CargoReadyService service;

    @BeforeEach
    void setUp() {
        table.clear();
        updateCalls = 0;
        service = new CargoReadyService(inMemoryMapper());
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

        CargoReadyResponse response = service.markReady(ORDER_ID);

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

        CargoReadyResponse first = service.markReady(ORDER_ID);
        int callsAfterFirst = updateCalls;
        CargoReadyResponse second = service.markReady(ORDER_ID);

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

        assertTrue(service.markReady("  " + ORDER_ID + " ").changed());
        assertFalse(service.markReady(ORDER_ID).changed(), "归一后是同一笔订单");
    }

    @Test
    @DisplayName("查无货物记录 → 拒绝，且不凭空建行")
    void missingGoods_shouldBeRejected() {
        CargoReadyResponse response = service.markReady(ORDER_ID);

        assertFalse(response.accepted());
        assertEquals(CargoReadyResponse.Reason.GOODS_NOT_FOUND.name(), response.reasonCode());
        assertEquals(0, updateCalls);
        assertTrue(table.isEmpty(), "不得 upsert：货物记录由下单落库时建立");
    }

    @Test
    @DisplayName("订单号缺失/空白 → ORDER_ID_MISSING（不是「查无货物」）")
    void blankOrderId_shouldBeRejected() {
        assertEquals(CargoReadyResponse.Reason.ORDER_ID_MISSING.name(),
                service.markReady(null).reasonCode());
        assertEquals(CargoReadyResponse.Reason.ORDER_ID_MISSING.name(),
                service.markReady("   ").reasonCode());
    }

    @Test
    @DisplayName("★ 已装机不得回退为已就绪（状态机不许倒退）")
    void loadedGoods_shouldNotRollBack() {
        seed("G-1", GoodsStatus.LOADED.getValue());

        CargoReadyResponse response = service.markReady(ORDER_ID);

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

        CargoReadyResponse response = service.markReady(ORDER_ID);

        assertTrue(response.changed());
        assertEquals(2, response.updatedRows());
        assertEquals(GoodsStatus.READY.getValue(), table.get(0).getStatus());
        assertEquals(GoodsStatus.READY.getValue(), table.get(1).getStatus());
        assertEquals(GoodsStatus.DELIVERED.getValue(), table.get(2).getStatus());
    }
}
