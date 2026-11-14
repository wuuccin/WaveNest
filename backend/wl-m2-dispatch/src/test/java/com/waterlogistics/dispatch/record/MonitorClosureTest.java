package com.waterlogistics.dispatch.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code record} 包六条缓置项的关闭留痕（11-14 序号 2-6）。
 *
 * <p>这六条（HIGH.1 / HIGH.2 / MEDIUM.6 / MEDIUM.7 / MEDIUM.9 / MEDIUM.56）在本批之前
 * 已由后续合并请求改好，但<b>没有测试钉住</b>。本类只做一件事：把它们「修好之后应当
 * 成立的行为」写成断言，防止以后有人把分页、聚合、上界、时区任何一处改回去而无人察觉。
 *
 * <p>这里<b>不重复实现</b>业务逻辑，只从外部观察：
 * <ul>
 *   <li>HTTP 入口对越界分页参数必须 400（HIGH.1）；</li>
 *   <li>列表查询必须带 {@code LIMIT}（HIGH.2）；</li>
 *   <li>统计必须走 {@code GROUP BY} 聚合而不是整表搬运（MEDIUM.6 / MEDIUM.7）；</li>
 *   <li>内存储留痕必须有订单数上界（MEDIUM.9）；</li>
 *   <li>落库时间必须按显式时区换算（MEDIUM.56）。</li>
 * </ul>
 */
class MonitorClosureTest {

    /** 记录最后一次列表查询的条件构造器，用于断言 SQL 里有没有 LIMIT。 */
    private static final AtomicReference<QueryWrapper<OrderTaskEntity>> LAST_QUERY =
            new AtomicReference<>();

    private static OrderTaskMapper orderTaskMapper() {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName()) && args != null
                            && args.length == 1 && args[0] instanceof QueryWrapper<?> wrapper) {
                        @SuppressWarnings("unchecked")
                        QueryWrapper<OrderTaskEntity> typed = (QueryWrapper<OrderTaskEntity>) wrapper;
                        LAST_QUERY.set(typed);
                        return List.<OrderTaskEntity>of();
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == List.class) {
                        return List.of();
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    private static TaskLogMapper taskLogMapper() {
        return (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == List.class) {
                        return List.of();
                    }
                    return rt == boolean.class ? false : null;
                });
    }

    private static MonitorQueryService service() {
        // statistics 走 JdbcTemplate，本类不验它的 SQL 结果，故注入 null；
        // 调用 statistics 的用例会自己换掉这一条路径（见 @DisplayName 说明）。
        return new MonitorQueryService(orderTaskMapper(), taskLogMapper(), null);
    }

    @Test
    @DisplayName("★ HIGH.1：越界分页参数一律 400，不接受「悄悄按上限截断」")
    void monitorTasks_shouldRejectOutOfRangePaging() {
        MonitorController controller = new MonitorController(service());

        assertThrows(ResponseStatusException.class, () -> controller.tasks(0, 50),
                "页码从 1 开始，0 非法");
        assertThrows(ResponseStatusException.class, () -> controller.tasks(1, 0),
                "页大小必须为正");
        assertThrows(ResponseStatusException.class,
                () -> controller.tasks(1, MonitorQueryService.MAX_PAGE_SIZE + 1),
                "超过上限应报错，而不是默默当成 200");
    }

    @Test
    @DisplayName("★ HIGH.1 / HIGH.2：默认与显式分页都会把 LIMIT 推到 SQL 上")
    void monitorTasks_shouldPushLimitDownToMapper() {
        MonitorQueryService service = service();

        service.tasks();
        String defaultSql = LAST_QUERY.get().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(defaultSql.contains("LIMIT"),
                "整表 selectList(null) 会把历史订单全搬进内存：" + defaultSql);

        service.tasks(3, 20);
        String pagedSql = LAST_QUERY.get().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(pagedSql.contains("LIMIT"),
                "分页参数必须落到 SQL 上，而不是取回来再截断：" + pagedSql);
        assertTrue(pagedSql.contains("40") && pagedSql.contains("20"),
                "第 3 页 20 条应对应 LIMIT 40, 20：" + pagedSql);
        assertTrue(pagedSql.contains("ORDER BY"), "翻页要稳定顺序：" + pagedSql);
    }

    @Test
    @DisplayName("★ HIGH.2：页大小上限被夹住，单次响应体不可能无限大")
    void monitorTasks_shouldClampPageSize() {
        MonitorQueryService service = service();

        service.tasks(1, 100_000);
        String sql = LAST_QUERY.get().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("LIMIT 0, " + MonitorQueryService.MAX_PAGE_SIZE),
                "超上限的 size 应被夹到 " + MonitorQueryService.MAX_PAGE_SIZE + "：" + sql);
    }

    @Test
    @DisplayName("★ MEDIUM.9：内存储留痕按订单数封顶，不随历史订单无限增长")
    void taskLogRecorder_shouldBoundTrackedOrders() {
        TaskLogRecorder recorder = new TaskLogRecorder();

        int over = 1_500;
        for (int index = 0; index < over; index++) {
            recorder.record("ORD-" + index, "订单接入", null, "待受理", 1_794_268_800_000L);
        }

        assertTrue(recorder.trackedOrderCount() <= 1_000,
                "订单数必须有上界（当前 " + recorder.trackedOrderCount() + "），否则是内存泄漏");
        assertTrue(recorder.entriesOf("ORD-" + (over - 1)).size() == 1,
                "最近一单必须在，淘汰的是最老的");
    }

    @Test
    @DisplayName("★ MEDIUM.56：落库时间按显式时区（Asia/Shanghai）换算，不跟 JVM 默认时区漂")
    void taskLogRecorder_shouldPersistWithExplicitZone() {
        AtomicReference<TaskLogEntity> persisted = new AtomicReference<>();
        TaskLogMapper capture = (TaskLogMapper) Proxy.newProxyInstance(
                TaskLogMapper.class.getClassLoader(), new Class<?>[]{TaskLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName()) && args != null && args.length == 1) {
                        persisted.set((TaskLogEntity) args[0]);
                        return 1;
                    }
                    Class<?> rt = method.getReturnType();
                    return rt == int.class ? 0 : null;
                });

        TaskLogRecorder recorder = new TaskLogRecorder(capture);
        long tsMs = 1_794_268_800_000L;
        recorder.record("ORD-TZ", "订单接入", null, "待受理", tsMs);

        TaskLogEntity entity = persisted.get();
        LocalDateTime expected = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(tsMs), ZoneId.of("Asia/Shanghai"));
        assertEquals(expected, entity.getTs(),
                "ts 必须按 Asia/Shanghai 换算；用 systemDefault 时换台机器就会漂 8 小时");
        assertEquals(8, expected.getHour(), "前提：该时刻在 +08:00 下是 08:00");
    }
}
