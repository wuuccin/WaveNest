package com.waterlogistics.dispatch.record;

import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 调度结果展示的<b>纯聚合逻辑</b>（M2 · 卢肄文 · 10-22 任务 D3）。
 *
 * <p>把 {@code order_task} 的行聚合成监控台要看的两份视图：任务列表与统计计数。
 * 本类<b>不碰 Spring／DB</b>——数据由调用方（{@link MonitorQueryService}）查出后传进来，
 * 所以「字段映射、排序、按状态计数、空库返回空列表」这些口径可以直接单测钉死，
 * 不必起数据库与消息队列。
 *
 * <h3>为什么单独抽一个纯类</h3>
 * <p>两条硬要求只有在纯函数里才验得彻底：
 * <ol>
 *   <li><b>空库时返回空列表而非报错</b>（D3 判据）——空集合/全 null 字段都不得抛；</li>
 *   <li><b>统计可核对</b>——12 种状态一个不落地列出（缺的补 0），
 *       而不是"只返回出现过的状态"（那样前端得自己补全，且前后端口径会飘）。</li>
 * </ol>
 *
 * <h3>状态词口径（关键，别绕回乱码坑）</h3>
 * <p>库里 {@code order_task.status} 存的是<b>中文状态词</b>（照 {@code 23} §3，
 * 由 {@code OrderStatus.getValue()} 写入）。本类同时给两个字段：
 * <ul>
 *   <li>{@code statusCode}——枚举名（{@code DISPATCHED} 等），供前端分支判断；</li>
 *   <li>{@code statusLabel}——库内中文原值，供展示。</li>
 * </ul>
 * 解析失败（历史脏值／手工改库）时 {@code statusCode} 给 {@value #UNKNOWN_CODE}、
 * {@code statusLabel} 仍照原样回显——<b>不吞掉异常值</b>，否则脏数据在监控台上会"消失"。
 */
public final class MonitorAggregator {

    /** 状态无法映射到枚举时使用的编码（保证前端有分支可用，且脏值可见）。 */
    public static final String UNKNOWN_CODE = "UNKNOWN";

    /** 统计里"未识别状态"的展示名。 */
    private static final String UNKNOWN_LABEL = "未识别状态";

    private MonitorAggregator() {
    }

    /**
     * 一条任务在监控台上的视图。
     *
     * @param orderId       订单号
     * @param statusCode    {@link OrderStatus} 枚举名；无法映射时为 {@value #UNKNOWN_CODE}
     * @param statusLabel   库内中文状态词原值
     * @param shipMmsi      船 MMSI
     * @param goodsType     货物类型
     * @param goodsWeightKg 载重，kg（库内为空时为 {@code null}）
     * @param priority      优先级编码（{@code NORMAL}／{@code URGENT}／{@code EMERGENCY}）
     * @param droneId       执行无人机；未派发时为 {@code null}
     * @param createTime    下单时刻
     * @param expectedTime  期望送达时刻
     * @param meetTime      派发时的会面（起飞）时刻；未派发时为 {@code null}
     * @param meetPoint     会面点坐标；任一坐标为 {@code null} 时整体为 {@code null}
     * @param failReason    失败／异常原因；无则为 {@code null}
     */
    public record TaskView(
            String orderId,
            String statusCode,
            String statusLabel,
            String shipMmsi,
            String goodsType,
            BigDecimal goodsWeightKg,
            String priority,
            String droneId,
            LocalDateTime createTime,
            LocalDateTime expectedTime,
            LocalDateTime meetTime,
            GeoPoint meetPoint,
            String failReason) {
    }

    /**
     * 任务列表响应。
     *
     * @param tasks 任务视图，按下单时刻<b>倒序</b>（最新在前，监控台先看最新）
     * @param total 条数（与 {@code tasks.size()} 一致，前端免于二次计数）
     */
    public record TasksResponse(List<TaskView> tasks, int total) {

        public TasksResponse {
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * 单个状态的计数。
     *
     * @param code  状态编码（枚举名，或 {@value MonitorAggregator#UNKNOWN_CODE}）
     * @param label 中文状态词
     * @param count 条数
     */
    public record StatusCount(String code, String label, int count) {
    }

    /**
     * 调度统计响应。
     *
     * @param totalOrders    订单总数
     * @param byStatus       按状态分组计数（{@link OrderStatus} 全量列出，缺的补 0）
     * @param dispatchedCount 处于「已派发」的订单数
     * @param inFlightCount  在飞（去程中／末段交接中／返航中）
     * @param finishedCount  已完成
     * @param terminalCount  终止态（已拒绝／失败／异常终止）
     * @param taskLogCount   {@code task_log} 事件条数（调度执行留痕总量）
     * @param lastOrderAt    最近一单的下单时刻；空库为 {@code null}
     */
    public record StatisticsResponse(
            int totalOrders,
            List<StatusCount> byStatus,
            int dispatchedCount,
            int inFlightCount,
            int finishedCount,
            int terminalCount,
            long taskLogCount,
            LocalDateTime lastOrderAt) {

        public StatisticsResponse {
            byStatus = List.copyOf(byStatus);
        }
    }

    /**
     * 行 → 任务视图列表（按下单时刻倒序；时刻为 null 的排最后）。
     *
     * @param orders 订单行，可为 {@code null} 或空
     * @return 视图列表；空输入返回空列表（<b>不抛异常</b>）
     */
    public static List<TaskView> toViews(List<OrderTaskEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<TaskView> views = new ArrayList<>(orders.size());
        for (OrderTaskEntity order : orders) {
            if (order == null) {
                continue;
            }
            views.add(toView(order));
        }
        views.sort(Comparator.comparing(TaskView::createTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return views;
    }

    /**
     * 行 → 任务列表响应。
     *
     * @param orders 订单行
     * @return 列表响应（空输入返回 {@code total=0} 的空列表）
     */
    public static TasksResponse toTasksResponse(List<OrderTaskEntity> orders) {
        List<TaskView> views = toViews(orders);
        return new TasksResponse(views, views.size());
    }

    /**
     * 行 → 统计响应。
     *
     * @param orders       订单行
     * @param taskLogCount {@code task_log} 条数
     * @return 统计响应；空输入时 {@code totalOrders=0}、各状态计数为 0
     */
    public static StatisticsResponse toStatistics(List<OrderTaskEntity> orders, long taskLogCount) {
        List<OrderTaskEntity> rows = orders == null ? List.of() : orders;

        int total = 0;
        int dispatched = 0;
        int inFlight = 0;
        int finished = 0;
        int terminal = 0;
        int unknown = 0;
        LocalDateTime lastOrderAt = null;

        // 以枚举为桶先计数，保证"12 种状态全列、缺的补 0"
        int[] buckets = new int[OrderStatus.values().length];

        for (OrderTaskEntity order : rows) {
            if (order == null) {
                continue;
            }
            total++;
            if (lastOrderAt == null || (order.getCreateTime() != null
                    && order.getCreateTime().isAfter(lastOrderAt))) {
                lastOrderAt = order.getCreateTime() != null ? order.getCreateTime() : lastOrderAt;
            }
            OrderStatus status = parse(order.getStatus());
            if (status == null) {
                unknown++;
                continue;
            }
            buckets[status.ordinal()]++;
            switch (status) {
                case DISPATCHED -> dispatched++;
                case OUTBOUND, HANDOVER, RETURNING -> inFlight++;
                case FINISHED -> finished++;
                default -> {
                    if (status.isTerminal()) {
                        terminal++;
                    }
                }
            }
        }

        List<StatusCount> byStatus = new ArrayList<>(OrderStatus.values().length + 1);
        OrderStatus[] all = OrderStatus.values();
        for (int i = 0; i < all.length; i++) {
            byStatus.add(new StatusCount(all[i].name(), all[i].getValue(), buckets[i]));
        }
        if (unknown > 0) {
            byStatus.add(new StatusCount(UNKNOWN_CODE, UNKNOWN_LABEL, unknown));
        }

        return new StatisticsResponse(total, byStatus, dispatched, inFlight, finished, terminal,
                taskLogCount, lastOrderAt);
    }

    // ───────────── 内部 ─────────────

    private static TaskView toView(OrderTaskEntity order) {
        OrderStatus status = parse(order.getStatus());
        String rawStatus = order.getStatus();
        return new TaskView(
                order.getOrderId(),
                status != null ? status.name() : UNKNOWN_CODE,
                rawStatus != null ? rawStatus : (status != null ? status.getValue() : null),
                order.getShipMmsi(),
                order.getGoodsType(),
                order.getGoodsWeight(),
                order.getPriority(),
                order.getDroneId(),
                order.getCreateTime(),
                order.getExpectedTime(),
                order.getMeetTime(),
                meetPointOf(order.getMeetLon(), order.getMeetLat()),
                order.getFailReason());
    }

    /** 会面点：任一坐标为 null 时整体为 null（<b>不臆造 0,0</b>——0,0 是几内亚湾，不是会面点）。 */
    private static GeoPoint meetPointOf(BigDecimal lon, BigDecimal lat) {
        if (lon == null || lat == null) {
            return null;
        }
        return new GeoPoint(lon.doubleValue(), lat.doubleValue());
    }

    /** 中文状态词 → 枚举；无法映射（空值／历史脏值）返回 {@code null}。 */
    private static OrderStatus parse(String statusValue) {
        if (statusValue == null || statusValue.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.fromValue(statusValue);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
