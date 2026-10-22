package com.waterlogistics.dispatch.record;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度结果展示的只读 HTTP 入口（M2 · 卢肄文 · 10-22 任务 D3）。
 *
 * <pre>
 *   GET /api/monitor/tasks        任务列表（状态、无人机、起飞时刻、会面点）
 *   GET /api/monitor/statistics   统计（按状态计数、在飞／完成／终止、事件条数）
 * </pre>
 *
 * <h3>★ 路径为什么是本地常量，而不是 {@code ApiPath} 常量</h3>
 * <p>契约 {@code ApiPath} 目前只有 {@code ORDERS}／{@code DRONES}／{@code NESTS}／
 * {@code INTERVENE}／{@code REPLAY_TEMPLATE}／{@code REPLAY_STATISTICS}，
 * <b>没有</b> {@code /api/monitor/*} 两项。按纪律「{@code wl-contract} 不私自改」，
 * 本类先以本地常量承载这两个路径，并在下面标出待登记项：
 * <b>待收口方在 {@code ApiPath} 登记后，本类只改这两行常量引用，别处不动。</b>
 *
 * <p>路径字面量本身与 10-22 清单给定的一致，也与 {@code ApiPath.REPLAY_STATISTICS}
 * 注释里「与调度侧 {@code /api/monitor/statistics} 分开」的表述对齐；
 * 与张睿哲的 D2（{@code /api/intervene}，已有契约常量）<b>不重名、不撞文件</b>：
 * 他只动 {@code dispatch/gate}＋干预控制层，本类在 {@code dispatch/record}。
 *
 * <h3>只读</h3>
 * <p>两个端点都只读 {@code order_task}／{@code task_log}，不写库、不改状态机、
 * 不触发调度。监控台拿到的永远是调度结果的<b>镜像</b>，不是新的真相来源。
 */
@RestController
public final class MonitorController {

    /** 任务列表路径——待 {@code ApiPath} 登记（见类注释）。 */
    static final String MONITOR_TASKS = "/api/monitor/tasks";

    /** 统计路径——待 {@code ApiPath} 登记（见类注释）。 */
    static final String MONITOR_STATISTICS = "/api/monitor/statistics";

    private final MonitorQueryService monitorQueryService;

    public MonitorController(MonitorQueryService monitorQueryService) {
        this.monitorQueryService = monitorQueryService;
    }

    /**
     * 任务列表（下单时刻倒序）。
     *
     * <p>空库返回 {@code {"tasks":[],"total":0}}，<b>不返回 404</b>——
     * 「库里还没有任务」与「这个任务不存在」是两件事，空列表是正确语义。
     */
    @GetMapping(MONITOR_TASKS)
    public MonitorAggregator.TasksResponse tasks() {
        return monitorQueryService.tasks();
    }

    /**
     * 调度统计。
     *
     * <p>按状态计数把 {@code OrderStatus} <b>12 种状态全部列出</b>（缺的补 0），
     * 前端不必自己补全；库内出现无法识别的状态值时另加一条 {@code UNKNOWN}，
     * 让脏数据可见而不是被吞掉。
     */
    @GetMapping(MONITOR_STATISTICS)
    public MonitorAggregator.StatisticsResponse statistics() {
        return monitorQueryService.statistics();
    }
}
