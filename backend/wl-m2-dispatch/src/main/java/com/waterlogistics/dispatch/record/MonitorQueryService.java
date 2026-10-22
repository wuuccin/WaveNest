package com.waterlogistics.dispatch.record;

import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 调度结果展示的查询服务（M2 · 卢肄文 · 10-22 任务 D3）。
 *
 * <p>只做「取数 → 交给 {@link MonitorAggregator} 聚合」这一层，<b>不含任何口径</b>：
 * 字段映射、排序、计数全在纯聚合器里，这样口径可单测、本类只剩一次查库。
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

    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;

    public MonitorQueryService(OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
    }

    /**
     * 任务列表（最新在前）。
     *
     * @return 列表响应；空库返回 {@code total=0} 的空列表
     */
    public MonitorAggregator.TasksResponse tasks() {
        return MonitorAggregator.toTasksResponse(selectAllOrders());
    }

    /**
     * 调度统计。
     *
     * @return 统计响应；空库时各计数为 0
     */
    public MonitorAggregator.StatisticsResponse statistics() {
        List<OrderTaskEntity> orders = selectAllOrders();
        Long logCount = taskLogMapper.selectCount(null);
        return MonitorAggregator.toStatistics(orders, logCount == null ? 0L : logCount);
    }

    /** 全量订单行；排序由聚合器负责（保持"排序口径只有一个地方"）。 */
    private List<OrderTaskEntity> selectAllOrders() {
        List<OrderTaskEntity> orders = orderTaskMapper.selectList(null);
        return orders == null ? List.of() : orders;
    }
}
