package com.waterlogistics.dispatch.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.GoodsMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CargoNestGate} 单测（11-05 序号 2 · 张睿哲）：货到巢关四分支 + 锁幂等。
 *
 * <p>无 Mockito：GoodsMapper 用 JDK 动态代理；OccupancySlotService 用子类覆写
 * （findActiveSlotByOrder / tryReserve），避免触库。</p>
 */
class CargoNestGateTest {

    private static final String ORDER_ID = "ORD-CARGO-1";
    private static final String NEST_ID = "NEST-A";

    private final List<GoodsEntity> goodsRows = new ArrayList<>();
    private StubOccupancy occupancy;
    private CargoNestGate gate;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GoodsEntity.class);
    }

    @BeforeEach
    void setUp() {
        goodsRows.clear();
        occupancy = new StubOccupancy();
        gate = new CargoNestGate(stubGoodsMapper(), occupancy, new SysParamLoader(null));
    }

    @Test
    @DisplayName("无货巢指定（cargoNestId=null/空串）→ PROCEED，不锁巢")
    void blankNest_proceedWithoutLock() {
        assertEquals(CargoNestGate.Decision.PROCEED, gate.evaluate(ORDER_ID, null));
        assertEquals(CargoNestGate.Decision.PROCEED, gate.evaluate(ORDER_ID, "  "));
        assertEquals(0, occupancy.reserveCalls, "无货巢时不应尝试锁巢");
    }

    @Test
    @DisplayName("货先到（已就绪+location=货巢）+ 锁得到 → PROCEED 且持锁")
    void readyCargo_reserveOk_proceed() {
        addGoods("已就绪", NEST_ID);
        occupancy.reserveOk = true;

        assertEquals(CargoNestGate.Decision.PROCEED, gate.evaluate(ORDER_ID, NEST_ID));
        assertEquals(1, occupancy.reserveCalls);
    }

    @Test
    @DisplayName("货后到（未就绪）+ 锁得到 → WAIT_CARGO（持锁等货）")
    void unreadyCargo_reserveOk_waitCargo() {
        addGoods("未就绪", NEST_ID);
        occupancy.reserveOk = true;

        assertEquals(CargoNestGate.Decision.WAIT_CARGO, gate.evaluate(ORDER_ID, NEST_ID));
        assertEquals(1, occupancy.reserveCalls);
    }

    @Test
    @DisplayName("货已就绪但 location 不在货巢 → 视为未就绪（口径与接单关一致）")
    void readyElsewhere_notReady() {
        addGoods("已就绪", "NEST-OTHER");
        occupancy.reserveOk = true;

        assertFalse(gate.isCargoReady(ORDER_ID, NEST_ID));
        assertEquals(CargoNestGate.Decision.WAIT_CARGO, gate.evaluate(ORDER_ID, NEST_ID));
    }

    @Test
    @DisplayName("巢满锁不到 → WAIT_RESOURCE（无论货是否就绪）")
    void reserveFails_waitResource() {
        occupancy.reserveOk = false;

        addGoods("已就绪", NEST_ID);
        assertEquals(CargoNestGate.Decision.WAIT_RESOURCE, gate.evaluate(ORDER_ID, NEST_ID));

        goodsRows.clear();
        addGoods("未就绪", NEST_ID);
        assertEquals(CargoNestGate.Decision.WAIT_RESOURCE, gate.evaluate(ORDER_ID, NEST_ID));
    }

    @Test
    @DisplayName("锁幂等：已有有效锁时不重复 tryReserve（唤醒重派路径）")
    void existingLock_noSecondReserve() {
        occupancy.hasSlot = true;
        addGoods("未就绪", NEST_ID);

        assertEquals(CargoNestGate.Decision.WAIT_CARGO, gate.evaluate(ORDER_ID, NEST_ID));
        assertEquals(0, occupancy.reserveCalls, "已有有效锁时不应再 tryReserve");
    }

    // ───────────────────────── stubs ─────────────────────────

    private void addGoods(String status, String location) {
        GoodsEntity g = new GoodsEntity();
        g.setGoodsId("G-" + goodsRows.size());
        g.setOrderId(ORDER_ID);
        g.setStatus(status);
        g.setLocation(location);
        goodsRows.add(g);
    }

    private GoodsMapper stubGoodsMapper() {
        return (GoodsMapper) Proxy.newProxyInstance(
                GoodsMapper.class.getClassLoader(), new Class<?>[]{GoodsMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        // 单测场景只按「已就绪 + location=NEST_ID」过滤（与门关查询口径一致）
                        return goodsRows.stream()
                                .filter(g -> "已就绪".equals(g.getStatus()))
                                .filter(g -> NEST_ID.equals(g.getLocation()))
                                .toList();
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class || rt == Integer.class) {
                        return 0;
                    }
                    if (rt == long.class || rt == Long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static class StubOccupancy extends OccupancySlotService {
        boolean hasSlot;
        boolean reserveOk;
        int reserveCalls;

        StubOccupancy() {
            super(null, null, null);
        }

        @Override
        public Optional<OccupancySlotEntity> findActiveSlotByOrder(String orderId) {
            return hasSlot ? Optional.of(new OccupancySlotEntity()) : Optional.empty();
        }

        @Override
        public Optional<String> tryReserve(String nestId, TimeWindow window, String orderId) {
            reserveCalls++;
            return reserveOk ? Optional.of("SLOT-STUB") : Optional.empty();
        }
    }
}
