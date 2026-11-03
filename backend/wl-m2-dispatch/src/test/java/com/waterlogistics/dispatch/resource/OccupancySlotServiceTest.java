package com.waterlogistics.dispatch.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.algo.inflight.TimeWindow;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OccupancySlotService} 单测（M2 · 宋冰鑫 · 占位释放）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code tryReserve} 成功后 slot 落库并带 {@code orderId}（C3 释放定位键）；</li>
 *   <li>{@code release} 把有效占位置「已释放」；</li>
 *   <li>{@code releaseByOrder} 按订单查到有效迫停占位并置「已释放」（M2 终态释放）；</li>
 *   <li>{@code releaseByOrder} 无匹配占位时静默幂等，不抛异常。</li>
 * </ul>
 *
 * <p>本模块测试无 Mockito 依赖，用 JDK 动态代理桩掉 MyBatis-Plus Mapper，
 * 占位行存在进程内 {@link #SLOTS} 列表里。
 */
class OccupancySlotServiceTest {

    private static final String NEST_ID = "NEST-FORCED";
    private static final String ORDER_ID = "ORD-001";

    private final List<OccupancySlotEntity> SLOTS = new ArrayList<>();

    private OccupancySlotService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaQueryWrapper/LambdaUpdateWrapper 需要实体的 lambda 缓存，单测无 Spring 环境需手动初始化。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OccupancySlotEntity.class);
    }

    @BeforeEach
    void setUp() {
        SLOTS.clear();
        service = new OccupancySlotService(stubSlotMapper(), stubNestMapper(), new SysParamLoader(null));
    }

    /** tryReserve 成功后，slot 落库且 orderId 已写入（供终态按订单释放）。 */
    @Test
    void tryReserve_shouldPersistOrderIdOnSlot() {
        Optional<String> slotId = service.tryReserve(NEST_ID, new TimeWindow(1_000L, 3_000L), ORDER_ID);

        assertTrue(slotId.isPresent(), "机巢可用且有空位，占位应成功");
        assertEquals(1, SLOTS.size(), "应落一行占位");
        OccupancySlotEntity slot = SLOTS.get(0);
        assertEquals(slotId.get(), slot.getSlotId());
        assertEquals(ORDER_ID, slot.getOrderId(), "slot 必须带 orderId，否则终态无法定位释放");
        assertEquals("迫停占用", slot.getObjectType());
        assertEquals(NEST_ID, slot.getObjectId());
        assertEquals("有效", slot.getStatus());
    }

    /** release 直接把有效占位置「已释放」（M2 门槛：release 以自身为入口的单测）。 */
    @Test
    void release_shouldTransitActiveSlotToReleased() {
        String slotId = service.tryReserve(NEST_ID, new TimeWindow(1_000L, 3_000L), ORDER_ID)
                .orElseThrow();
        assertEquals("有效", SLOTS.get(0).getStatus(), "前置：占位为有效");

        service.release(slotId);

        assertEquals("已释放", SLOTS.get(0).getStatus(),
                "release 后占位应由「有效」转「已释放」");
    }

    /** releaseByOrder 按订单查到有效迫停占位并置「已释放」（M2 终态释放主路径）。 */
    @Test
    void releaseByOrder_shouldReleaseActiveForcedStopSlotOfOrder() {
        service.tryReserve(NEST_ID, new TimeWindow(1_000L, 3_000L), ORDER_ID);
        assertEquals("有效", SLOTS.get(0).getStatus(), "前置：占位为有效");

        service.releaseByOrder(ORDER_ID);

        assertEquals("已释放", SLOTS.get(0).getStatus(),
                "订单终态后，该订单的迫停占位应由「有效」转「已释放」");
    }

    /** releaseByOrder 无任何占位时静默返回，幂等不抛（C3 释放失败处置口径）。 */
    @Test
    void releaseByOrder_withoutAnySlot_shouldBeNoOp() {
        // 不创建任何占位，直接释放一个不存在的订单占位
        service.releaseByOrder(ORDER_ID);

        // 无异常即通过：releaseByOrder 对空结果集静默返回
        assertTrue(SLOTS.isEmpty(), "无占位时不应产生任何行");
    }

    // ───────────────────────── fake mappers ─────────────────────────

    private OccupancySlotMapper stubSlotMapper() {
        return (OccupancySlotMapper) Proxy.newProxyInstance(
                OccupancySlotMapper.class.getClassLoader(),
                new Class<?>[]{OccupancySlotMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    Class<?> rt = method.getReturnType();
                    return switch (name) {
                        case "insert" -> {
                            OccupancySlotEntity e = (OccupancySlotEntity) args[0];
                            SLOTS.add(e);
                            yield 1;
                        }
                        case "selectById" -> SLOTS.stream()
                                .filter(s -> s.getSlotId().equals(args[0]))
                                .findFirst().orElse(null);
                        case "selectList" -> {
                            // releaseByOrder 查有效迫停占位；单测场景直接返回全部有效迫停占位。
                            yield SLOTS.stream()
                                    .filter(s -> "迫停占用".equals(s.getObjectType())
                                            && "有效".equals(s.getStatus()))
                                    .toList();
                        }
                        case "selectCount" -> 0L;
                        case "update" -> {
                            // release: eq slotId + eq 有效 → set 已释放。
                            // 单测同时只有一个有效占位，直接把首个「有效」占位置为「已释放」。
                            SLOTS.stream()
                                    .filter(s -> "有效".equals(s.getStatus()))
                                    .findFirst()
                                    .ifPresent(s -> s.setStatus("已释放"));
                            yield 1;
                        }
                        default -> rt == int.class ? 0 : rt == long.class ? 0L : null;
                    };
                });
    }

    private NestMapper stubNestMapper() {
        return (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(),
                new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        NestEntity nest = new NestEntity();
                        nest.setNestId(NEST_ID);
                        nest.setStatus("可用");
                        nest.setCapacity(4);
                        return nest;
                    }
                    return null;
                });
    }
}
