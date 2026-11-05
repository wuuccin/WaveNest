package com.waterlogistics.dispatch.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.waterlogistics.contract.order.OrderView;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FlightPhaseAdvancer} 的两条回归口径（2026-11-01 补）：
 *
 * <ol>
 *   <li><b>订单态变化不得让实时出口丢单</b>：缓存标脏而非删除，否则「派发已提交、首拍未到」
 *       的窗口内 {@link FlightPhaseAdvancer#activeOrderIdOf} 返回空；</li>
 *   <li><b>审计时刻不得倒挂</b>：补记历史迁移、位置驱动提前切入与时间轴混用时，
 *       {@code task_log.ts} 必须严格递增且不早于下单时刻。</li>
 * </ol>
 *
 * <p>模块内无 Mockito，故用动态代理实现 mapper 桩：只实现被测路径用到的方法，
 * 其余按返回类型给默认值。
 */
class FlightPhaseAdvancerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定时钟，全部用例共享，避免真实时间让断言漂移。 */
    private static final long NOW = 1_760_000_000_000L;

    private static final double NEST_LON = 0.0;
    private static final double NEST_LAT = 0.0;

    /** 会面点：正北 0.01°，约 1113 m；巡航 10 m/s → 去程约 111 s。 */
    private static final double MEET_LON = 0.0;
    private static final double MEET_LAT = 0.01;

    /**
     * 被测代码用 {@code LambdaQueryWrapper}／{@code LambdaUpdateWrapper}，其列名解析依赖
     * MyBatis-Plus 的实体元数据缓存；无 Spring 上下文时须手动装一次，否则抛
     * 「can not find lambda cache for this entity」。
     */
    @BeforeAll
    static void initLambdaColumnCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new Configuration(), "flight-phase-advancer-test");
        TableInfoHelper.initTableInfo(assistant, OrderTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskLogEntity.class);
    }

    @Test
    @DisplayName("★ 订单态变化后不丢单：缓存标脏而非删除，实时出口仍能取到在飞订单")
    void orderStateChanged_shouldNotBlankActiveOrder() {
        List<TaskLogEntity> inserted = new ArrayList<>();
        FlightPhaseAdvancer advancer = advancer(
                List.of(order(NOW + 3_600_000L, NOW - 600_000L)),
                dispatchLog(NOW - 600_000L),
                List.of(),
                inserted,
                null);

        advancer.tick();
        assertEquals("O1", advancer.activeOrderIdOf("D1"), "首拍后应能按机号取到在飞订单");
        assertEquals(1, advancer.trackedCycleCount());

        advancer.onOrderStateChanged(new OrderStateChangedEvent(
                new OrderView("O1", OrderStatus.DISPATCHED, NOW, 2L, null, null, null, null)));

        assertEquals("O1", advancer.activeOrderIdOf("D1"),
                "订单态变化只标脏，删掉会让实时出口在重建窗口内丢单");
        assertEquals(1, advancer.trackedCycleCount(), "标脏不删除");

        advancer.tick();
        assertEquals("O1", advancer.activeOrderIdOf("D1"), "重建后仍可取到");
        assertEquals(1, advancer.trackedCycleCount());
        assertTrue(inserted.isEmpty(), "该周期尚未到期，不应写审计");
    }

    @Test
    @DisplayName("★ 补记历史迁移：审计时刻严格递增，且不早于下单时刻（不倒退）")
    void backlogAudit_shouldBeMonotonicAndNotBeforeCreateTime() {
        long meetMs = NOW - 3_500_000L;
        // 下单即临期：倒推的起飞时刻（meet − 111 s）早于下单时刻
        long createMs = NOW - 3_590_000L;
        List<TaskLogEntity> inserted = new ArrayList<>();
        FlightPhaseAdvancer advancer = advancer(
                List.of(order(meetMs, createMs)), dispatchLog(createMs), List.of(), inserted, null);

        advancer.tick();

        assertEquals(4, inserted.size(), "四段迁移全部补记");
        long previous = createMs;
        for (TaskLogEntity log : inserted) {
            long ts = epochMs(log.getTs());
            assertTrue(ts > previous,
                    "审计时刻必须严格递增：" + log.getOpType() + " ts=" + ts + " 上一条=" + previous);
            previous = ts;
        }
        assertEquals(0, advancer.trackedCycleCount(), "推进到「已完成」当拍即淘汰缓存");
    }

    @Test
    @DisplayName("★ 位置提前切入后，时间轴仍按到期时刻补记：不得晚写后被更早的时刻覆盖")
    void positionHandover_thenTimeline_shouldKeepAuditMonotonic() {
        long meetMs = NOW - 300_000L;
        long createMs = NOW - 900_000L;
        TaskLogEntity lastFlight = flightLog("O1", OrderStatus.OUTBOUND, NOW - 600_000L);
        List<TaskLogEntity> inserted = new ArrayList<>();

        FlightPhaseAdvancer advancer = advancer(
                List.of(order(meetMs, createMs)),
                dispatchLog(createMs),
                List.of(lastFlight),
                inserted,
                // 位置已在会面点上（距离 0），必定触发位置切入
                droneId -> Optional.of(new DronePositionPort.Position(MEET_LON, MEET_LAT)));

        advancer.tick();

        assertEquals(3, inserted.size(), "位置切入 + 返航 + 落巢");
        assertEquals(List.of("末段切入（位置）", "交接完成", "落巢完成"),
                inserted.stream().map(TaskLogEntity::getOpType).toList());

        long previous = epochMs(lastFlight.getTs());
        for (TaskLogEntity log : inserted) {
            long ts = epochMs(log.getTs());
            assertTrue(ts > previous,
                    "位置切入用当前时刻、时间轴用到期时刻，混用后必须钳制："
                            + log.getOpType() + " ts=" + ts + " 上一条=" + previous);
            previous = ts;
        }
        assertEquals(0, advancer.trackedCycleCount(), "推进到「已完成」当拍即淘汰缓存");
    }

    @Test
    @DisplayName("★ ETA 落库：从派发到完成至少写 3 次、逐次不减、末值＝会面时刻＋交接时长")
    void etaTs_shouldBeWrittenMonotonicallyAndEndAtDelivered() {
        long meetMs = NOW - 300_000L;
        long createMs = NOW - 900_000L;
        List<Long> etas = new ArrayList<>();
        FlightPhaseAdvancer advancer = advancer(
                List.of(order(meetMs, createMs)), dispatchLog(createMs), List.of(),
                new ArrayList<>(), null, etas);

        advancer.tick();

        // 夹具无位置端口 → 飞中 ETA 回落到「会面 + 交接」，四段迁移各写一次
        assertTrue(etas.size() >= 3,
                "《11-05 任务清单》§八 硬判据 4：从下单到完成至少更新 3 次，实际 " + etas.size());
        for (int i = 1; i < etas.size(); i++) {
            assertTrue(etas.get(i) >= etas.get(i - 1), "ETA 必须单调不减：" + etas);
        }
        // 交接时长取 sys_param 默认值 120 s（测试的 SysParamLoader 无库，走默认）
        assertEquals(meetMs + 120_000L, etas.get(etas.size() - 1),
                "末值＝实际送达时刻＝会面时刻＋交接时长，实际序列 " + etas);
    }

    // ---------------------------------------------------------------- 被测对象装配

    private static FlightPhaseAdvancer advancer(List<OrderTaskEntity> orders,
            TaskLogEntity dispatchLog, List<TaskLogEntity> history,
            List<TaskLogEntity> inserted, DronePositionPort positionPort) {
        return advancer(orders, dispatchLog, history, inserted, positionPort, null);
    }

    /**
     * 带 ETA 采集的装配：{@code etaSink} 非空时，每次 {@code order_task} 更新里写入的
     * {@code eta_ts} 都会按写入顺序追加进去（{@code LambdaUpdateWrapper} 的参数值里
     * 只有 ETA 是 {@code LocalDateTime}，可直接识别）。
     */
    private static FlightPhaseAdvancer advancer(List<OrderTaskEntity> orders,
            TaskLogEntity dispatchLog, List<TaskLogEntity> history,
            List<TaskLogEntity> inserted, DronePositionPort positionPort, List<Long> etaSink) {
        DroneContextPort droneContextPort =
                droneId -> new DroneContext("D1", "N1", "N1", NEST_LON, NEST_LAT, 10.0, "C1");
        return new FlightPhaseAdvancer(
                orderTaskMapper(orders, etaSink),
                taskLogMapper(dispatchLog, history, inserted),
                droneContextPort,
                positionPort,
                new SysParamLoader(null),
                event -> {
                },
                null,
                null,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZONE),
                null);
    }

    private static OrderTaskEntity order(long meetMs, long createMs) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId("O1");
        order.setShipMmsi("413000001");
        order.setStatus(OrderStatus.DISPATCHED.getValue());
        order.setDroneId("D1");
        order.setCreateTime(at(createMs));
        order.setMeetTime(at(meetMs));
        order.setMeetLon(BigDecimal.valueOf(MEET_LON));
        order.setMeetLat(BigDecimal.valueOf(MEET_LAT));
        return order;
    }

    private static TaskLogEntity dispatchLog(long tsMs) {
        TaskLogEntity log = new TaskLogEntity();
        log.setLogId(1L);
        log.setOrderId("O1");
        log.setOpType("派发");
        log.setToStatus(OrderStatus.DISPATCHED.getValue());
        log.setTs(at(tsMs));
        return log;
    }

    private static TaskLogEntity flightLog(String orderId, OrderStatus status, long tsMs) {
        TaskLogEntity log = new TaskLogEntity();
        log.setLogId(2L);
        log.setOrderId(orderId);
        log.setOpType("阶段推进");
        log.setToStatus(status.getValue());
        log.setTs(at(tsMs));
        return log;
    }

    // ---------------------------------------------------------------- mapper 桩

    private static OrderTaskMapper orderTaskMapper(List<OrderTaskEntity> orders) {
        return orderTaskMapper(orders, null);
    }

    private static OrderTaskMapper orderTaskMapper(List<OrderTaskEntity> orders,
            List<Long> etaSink) {
        return stub(OrderTaskMapper.class, (proxy, method, args) -> switch (method.getName()) {
            case "selectList" -> orders;
            case "update" -> {
                if (etaSink != null && args != null && args.length >= 2
                        && args[1] instanceof LambdaUpdateWrapper<?> wrapper) {
                    for (Object value : wrapper.getParamNameValuePairs().values()) {
                        if (value instanceof LocalDateTime time) {
                            etaSink.add(epochMs(time));
                        }
                    }
                }
                yield 1;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static TaskLogMapper taskLogMapper(TaskLogEntity dispatchLog,
            List<TaskLogEntity> history, List<TaskLogEntity> inserted) {
        AtomicLong sequence = new AtomicLong(100L);
        return stub(TaskLogMapper.class, (proxy, method, args) -> switch (method.getName()) {
            case "selectOne" -> dispatchLog;
            case "selectList" -> history;
            case "insert" -> {
                TaskLogEntity entry = (TaskLogEntity) args[0];
                entry.setLogId(sequence.incrementAndGet());
                inserted.add(entry);
                yield 1;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == double.class) {
            return 0.0;
        }
        return null;
    }

    private static LocalDateTime at(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }

    private static long epochMs(LocalDateTime time) {
        return time.atZone(ZONE).toInstant().toEpochMilli();
    }
}
