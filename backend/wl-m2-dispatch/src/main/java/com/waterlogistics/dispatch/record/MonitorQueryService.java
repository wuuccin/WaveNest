package com.waterlogistics.dispatch.record;

import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度结果展示的查询服务（M2 · 卢肄文 · 10-22 任务 D3）。
 *
 * <p>列表只读取请求页；统计在数据库聚合后交给 {@link MonitorAggregator} 统一映射状态口径。
 *
 * <h3>数据来源（按清单要求：走已有持久层，不新造表）</h3>
 * <ul>
 *   <li>{@code order_task}——订单与派发结果（状态／无人机／起飞时刻／会面点）；</li>
 *   <li>{@code task_log}——调度事件留痕条数（只取总数，不在此展开成时间线）。</li>
 * </ul>
 *
 * <p><b>读别人写的表</b>：两表由 {@code dispatch.queue}（张睿哲）在状态流转时写入，
 * 本类<b>只读</b>、不写、不改状态机——展示端点绝不反向驱动调度。
 */
@Service
public class MonitorQueryService {
    /** 默认 50 行，最多 200 行；页码从 1 开始。 */
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final JdbcTemplate jdbcTemplate;

    public MonitorQueryService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            JdbcTemplate jdbcTemplate) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 任务列表（最新在前）。
     *
     * @return 列表响应；空库返回 {@code total=0} 的空列表
     */
    public MonitorAggregator.TasksResponse tasks() {
        return tasks(1, DEFAULT_PAGE_SIZE);
    }

    public MonitorAggregator.TasksResponse tasks(int page, int size) {
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        long offset = ((long) boundedPage - 1) * boundedSize;
        // 只取请求页，使用主键打破相同 create_time 的排序并保持翻页稳定。
        QueryWrapper<OrderTaskEntity> query = new QueryWrapper<OrderTaskEntity>()
                .orderByDesc("create_time", "order_id")
                .last("LIMIT " + offset + ", " + boundedSize);
        List<OrderTaskEntity> orders = orderTaskMapper.selectList(query);
        return MonitorAggregator.toTasksResponse(orders == null ? List.of() : orders);
    }

    /**
     * 调度统计。
     *
     * @return 统计响应；空库时各计数为 0
     */
    @Transactional(readOnly = true)
    public MonitorAggregator.StatisticsResponse statistics() {
        // 订单聚合与任务日志计数共用只读事务，避免跨事务读取。
        Map<String, Long> counts = new java.util.HashMap<>();
        LocalDateTime[] lastOrderAt = {null};
        jdbcTemplate.query("SELECT status, COUNT(*) AS amount, MAX(create_time) AS latest "
                        + "FROM order_task GROUP BY status", rs -> {
            String status = rs.getString("status");
            counts.put(status == null ? "" : status, rs.getLong("amount"));
            java.sql.Timestamp latest = rs.getTimestamp("latest");
            if (latest != null && (lastOrderAt[0] == null
                    || latest.toLocalDateTime().isAfter(lastOrderAt[0]))) {
                lastOrderAt[0] = latest.toLocalDateTime();
            }
        });
        Long logCount = taskLogMapper.selectCount(null);
        // task_log 属另一张持续写入的事件表，其计数与订单聚合不承诺同一时刻快照。
        return MonitorAggregator.toStatistics(counts, logCount == null ? 0L : logCount, lastOrderAt[0]);
    }
}
