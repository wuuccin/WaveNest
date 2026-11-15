package com.waterlogistics.dispatch.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 占位与释放的并发回归（11-14 序号 2-4 · 张睿哲）。
 *
 * <p>钉住三条容易回退的口径：
 * <ol>
 *   <li>重派（requeue → 再编排）带同一订单号再占同一架机时，<b>先释放本单本机旧锁再插入</b>，
 *       否则重叠检查会把自己的旧锁算成并发冲突，二次派发必失败；</li>
 *   <li>旧锁释放只按 {@code (orderId, objectType, objectId)} 精确匹配，<b>不动其他订单</b>
 *       （多单同机不同时窗、多船并行场景下不得互相误释放）；</li>
 *   <li>{@code releaseByOrder} 只释放本单的机占用与迫停占用，<b>不波及同机其他订单</b>。</li>
 * </ol>
 *
 * <p>用动态代理桩替 Mapper（模块无 Mockito 依赖，与 {@code OccupancySlotServiceTest} 同法），
 * 不连数据库；SQL 层的命名锁与重叠检查由集成环境覆盖。
 */
class OccupancySlotRedispatchTest {

    private static final String ORDER_A = "ORD-A";
    private static final String ORDER_B = "ORD-B";
    private static final String DRONE = "UAV-02";
    private static final String TYPE_DRONE = "机占用";
    private static final String TYPE_FORCED_STOP = "迫停占用";
    private static final String ACTIVE = "有效";
    private static final String RELEASED = "已释放";

    private final List<OccupancySlotEntity> slots = new ArrayList<>();
    private Predicate<OccupancySlotEntity> listFilter = slot -> false;
    private String lastSelectedById;

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
        slots.clear();
        lastSelectedById = null;
        service = new OccupancySlotService(stubSlotMapper(), stubNestMapper(),
                new SysParamLoader(null));
    }

    @Test
    @DisplayName("重派同单同机：先释放本单旧锁再插入，新锁有效且旧锁已释放")
    void occupyForOrder_shouldReleaseOwnPreviousSlotBeforeReinsert() {
        slots.add(slot("SLOT-OLD", ORDER_A, TYPE_DRONE, DRONE, ACTIVE));
        listFilter = slot -> "有效".equals(slot.getStatus())
                && ORDER_A.equals(slot.getOrderId())
                && TYPE_DRONE.equals(slot.getObjectType())
                && DRONE.equals(slot.getObjectId());

        String newSlotId = service.occupyForOrder(TYPE_DRONE, DRONE,
                at(10), at(11), "常规驻留", ORDER_A);

        assertEquals(RELEASED, slotOf("SLOT-OLD").getStatus(), "本单同机旧锁须先释放");
        assertEquals(ACTIVE, slotOf(newSlotId).getStatus(), "新锁须落为有效");
        assertNotEquals("SLOT-OLD", newSlotId);
        assertEquals(1, activeCountOf(ORDER_A), "同一订单同一架机最终只允许一条有效占位");
    }

    @Test
    @DisplayName("多单同机：重派只释放本单旧锁，不误释放其他订单的有效占位")
    void occupyForOrder_shouldNotReleaseOtherOrdersSlot() {
        slots.add(slot("SLOT-A", ORDER_A, TYPE_DRONE, DRONE, ACTIVE));
        slots.add(slot("SLOT-B", ORDER_B, TYPE_DRONE, DRONE, ACTIVE));
        listFilter = slot -> "有效".equals(slot.getStatus())
                && ORDER_A.equals(slot.getOrderId())
                && TYPE_DRONE.equals(slot.getObjectType())
                && DRONE.equals(slot.getObjectId());

        String newSlotId = service.occupyForOrder(TYPE_DRONE, DRONE,
                at(12), at(13), "常规驻留", ORDER_A);

        assertEquals(RELEASED, slotOf("SLOT-A").getStatus(), "本单旧锁须释放");
        assertEquals(ACTIVE, slotOf("SLOT-B").getStatus(), "其他订单的占位不得被牵连释放");
        assertEquals(ACTIVE, slotOf(newSlotId).getStatus());
        assertEquals(1, activeCountOf(ORDER_B), "订单 B 仍须保持一条有效占位");
    }

    @Test
    @DisplayName("多单释放：releaseByOrder 只释放本单的机占用与迫停占用")
    void releaseByOrder_shouldOnlyReleaseTargetOrder() {
        slots.add(slot("SLOT-A-DRONE", ORDER_A, TYPE_DRONE, DRONE, ACTIVE));
        slots.add(slot("SLOT-A-STOP", ORDER_A, TYPE_FORCED_STOP, "NEST-03", ACTIVE));
        slots.add(slot("SLOT-B-DRONE", ORDER_B, TYPE_DRONE, DRONE, ACTIVE));
        listFilter = slot -> "有效".equals(slot.getStatus()) && ORDER_A.equals(slot.getOrderId());

        service.releaseByOrder(ORDER_A);

        assertEquals(RELEASED, slotOf("SLOT-A-DRONE").getStatus());
        assertEquals(RELEASED, slotOf("SLOT-A-STOP").getStatus(), "迫停占用同属本单，须一并释放");
        assertEquals(ACTIVE, slotOf("SLOT-B-DRONE").getStatus(), "其他订单同机占位不得被释放");
        assertEquals(0, activeCountOf(ORDER_A));
        assertEquals(1, activeCountOf(ORDER_B));
    }

    @Test
    @DisplayName("释放后时窗空出：同机下一单可直接占位，不再被判重叠")
    void afterRelease_shouldAllowNextOrderToOccupySameDrone() {
        slots.add(slot("SLOT-A", ORDER_A, TYPE_DRONE, DRONE, ACTIVE));
        listFilter = slot -> "有效".equals(slot.getStatus()) && ORDER_A.equals(slot.getOrderId());
        service.releaseByOrder(ORDER_A);

        listFilter = slot -> "有效".equals(slot.getStatus());
        String slotB = service.occupyForOrder(TYPE_DRONE, DRONE,
                at(14), at(15), "常规驻留", ORDER_B);

        assertEquals(ACTIVE, slotOf(slotB).getStatus());
        assertTrue(activeCountOf(ORDER_B) == 1);
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private static LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 11, 14, hour, 0, 0);
    }

    private static OccupancySlotEntity slot(String slotId, String orderId,
            String objectType, String objectId, String status) {
        OccupancySlotEntity slot = new OccupancySlotEntity();
        slot.setSlotId(slotId);
        slot.setOrderId(orderId);
        slot.setObjectType(objectType);
        slot.setObjectId(objectId);
        slot.setStatus(status);
        return slot;
    }

    private OccupancySlotEntity slotOf(String slotId) {
        return slots.stream().filter(s -> slotId.equals(s.getSlotId())).findFirst().orElseThrow();
    }

    private long activeCountOf(String orderId) {
        return slots.stream().filter(s -> orderId.equals(s.getOrderId())
                && ACTIVE.equals(s.getStatus())).count();
    }

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
                            slots.add(e);
                            yield 1;
                        }
                        case "selectById" -> {
                            lastSelectedById = (String) args[0];
                            yield slots.stream()
                                    .filter(s -> lastSelectedById.equals(s.getSlotId()))
                                    .findFirst().orElse(null);
                        }
                        case "selectList" -> slots.stream().filter(listFilter).toList();
                        case "selectCount" -> 0L;
                        case "update" -> {
                            // release: eq slotId + eq 有效 → set 已释放；桩按 selectById 记下的 slotId 精确落状态
                            OccupancySlotEntity target = slots.stream()
                                    .filter(s -> s.getSlotId() != null && s.getSlotId().equals(lastSelectedById))
                                    .findFirst().orElse(null);
                            if (target == null) {
                                yield 0;
                            }
                            target.setStatus(RELEASED);
                            yield 1;
                        }
                        case "getLock" -> 1;
                        case "releaseLock" -> 1;
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
                        nest.setNestId("NEST-01");
                        nest.setStatus("可用");
                        nest.setCapacity(4);
                        return nest;
                    }
                    return null;
                });
    }
}
