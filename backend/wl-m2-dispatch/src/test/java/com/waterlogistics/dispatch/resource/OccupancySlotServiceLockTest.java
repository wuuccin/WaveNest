package com.waterlogistics.dispatch.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OccupancySlotService#occupy} 命名锁改造（HIGH.33 · 11-05 序号 2）。
 *
 * <p>钉住三件事：拿锁成功才执行查重+插入；拿不到锁抛冲突；重叠仍拒绝且 finally 释锁。
 * GET_LOCK/RELEASE_LOCK 由代理桩记录，不触真实 MySQL（集成侧由全量构建覆盖）。</p>
 */
class OccupancySlotServiceLockTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 5, 10, 5);

    private final List<OccupancySlotEntity> slots = new ArrayList<>();
    private final AtomicInteger getLockCalls = new AtomicInteger();
    private final AtomicInteger releaseLockCalls = new AtomicInteger();
    private final AtomicBoolean lockGranted = new AtomicBoolean(true);
    private final AtomicBoolean overlap = new AtomicBoolean(false);

    private OccupancySlotService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OccupancySlotEntity.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        slots.clear();
        getLockCalls.set(0);
        releaseLockCalls.set(0);
        lockGranted.set(true);
        overlap.set(false);
        service = new OccupancySlotService(stubSlotMapper(), stubNestMapper(), new SysParamLoader(null));
    }

    @Test
    @DisplayName("occupy：先 GET_LOCK 再查重插入，结束后 RELEASE_LOCK")
    void occupy_acquiresAndReleasesNamedLock() {
        String slotId = service.occupy("机占用", "UAV-1", T1, T2, "常规驻留");

        assertNotNull(slotId);
        assertTrue(getLockCalls.get() > 0, "必须先拿命名锁");
        assertTrue(releaseLockCalls.get() > 0, "finally 必须释锁");
        assertFalse(slots.isEmpty(), "占位行应落库");
    }

    @Test
    @DisplayName("occupy：命名锁 5s 内拿不到 → IllegalStateException，不落行")
    void occupy_lockTimeout_throws() {
        lockGranted.set(false);

        assertThrows(IllegalStateException.class,
                () -> service.occupy("机占用", "UAV-1", T1, T2, "常规驻留"));
        assertTrue(slots.isEmpty(), "拿不到锁不得插入占位行");
    }

    @Test
    @DisplayName("occupy：时窗重叠仍拒绝（锁内查重），且锁照样释放")
    void occupy_overlap_stillRejected() {
        overlap.set(true);

        assertThrows(IllegalStateException.class,
                () -> service.occupy("机占用", "UAV-1", T1, T2, "常规驻留"));
        assertTrue(releaseLockCalls.get() > 0, "冲突路径也必须释锁");
        assertTrue(slots.isEmpty());
    }

    @Test
    @DisplayName("命名锁名：≤64 字符原样；超长截断+散列且稳定")
    void occupyLockName_stableAndBounded() {
        String shortName = OccupancySlotService.occupyLockName("机占用", "UAV-1");
        assertTrue(shortName.length() <= 64);

        String longObjectId = "X".repeat(80);
        String n1 = OccupancySlotService.occupyLockName("归巢时隙", longObjectId);
        String n2 = OccupancySlotService.occupyLockName("归巢时隙", longObjectId);
        assertTrue(n1.length() <= 64, "锁名不得超 MySQL 64 字符上限");
        assertTrue(n1.equals(n2), "同一对象锁名必须稳定一致");
    }

    // ───────────────────────── stubs ─────────────────────────

    private OccupancySlotMapper stubSlotMapper() {
        return (OccupancySlotMapper) Proxy.newProxyInstance(
                OccupancySlotMapper.class.getClassLoader(), new Class<?>[]{OccupancySlotMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLock" -> {
                        getLockCalls.incrementAndGet();
                        yield lockGranted.get() ? 1 : 0;
                    }
                    case "releaseLock" -> {
                        releaseLockCalls.incrementAndGet();
                        yield 1;
                    }
                    case "selectCount" -> overlap.get() ? 1L : 0L;
                    case "insert" -> {
                        slots.add((OccupancySlotEntity) args[0]);
                        yield 1;
                    }
                    default -> method.getReturnType() == int.class ? 0
                            : method.getReturnType() == long.class ? 0L : null;
                });
    }

    private NestMapper stubNestMapper() {
        return (NestMapper) Proxy.newProxyInstance(
                NestMapper.class.getClassLoader(), new Class<?>[]{NestMapper.class},
                (proxy, method, args) -> null);
    }
}
