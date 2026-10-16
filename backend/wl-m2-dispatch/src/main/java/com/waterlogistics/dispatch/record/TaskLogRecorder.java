package com.waterlogistics.dispatch.record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 调度结果记录器（M2 · 卢肄文 · 模块二功能 11「调度结果记录与展示」）。
 *
 * <p>写 {@code task_log} 事件流（表结构见 {@code 37} §5.13，字段一字不改）：
 * {@code order_id}／{@code op_type}／{@code from_status}／{@code to_status}／{@code ts}。
 *
 * <p><b>今天不落库</b>（分工表：除张睿哲外其他人可以不落库）：先落进程内事件流 ＋ 日志，
 * 供当日联调查看。待 {@code wl-storage} 的 {@code task_log} 实体与 Mapper 就绪后，
 * 只在本类内补一个 Repository 注入并持久化，<b>调用方签名不变</b>。
 *
 * <p>状态词一律用 {@code 23} 的中文状态词（如「待受理」「已受理待唤醒」），
 * 与 {@code order_task.status} 落库值一致；不得自造（{@code 23} §9 禁用表）。
 */
@Component
public class TaskLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(TaskLogRecorder.class);

    /** 事件流上限：仅内存用，防止长时间运行无限增长（落库后本限制可去掉）。 */
    private static final int MAX_ENTRIES = 5_000;

    private final Map<String, List<TaskLogEntry>> entriesByOrder = new ConcurrentHashMap<>();

    /**
     * 记录一次状态／展示数据变化。
     *
     * @param orderId    订单标识
     * @param opType     操作类型，如「受理判定」「状态流转」
     * @param fromStatus 变更前状态（可为 null，表示首次记录）
     * @param toStatus   变更后状态
     * @param tsMs       发生时刻，Unix 毫秒
     */
    public void record(String orderId, String opType, String fromStatus, String toStatus, long tsMs) {
        TaskLogEntry entry = new TaskLogEntry(orderId, opType, fromStatus, toStatus, tsMs);

        List<TaskLogEntry> entries =
                entriesByOrder.computeIfAbsent(orderId, key -> new ArrayList<>());
        synchronized (entries) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.remove(0);
            }
            entries.add(entry);
        }

        log.info("task_log：orderId={} op={} {} -> {} ts={}",
                orderId, opType, fromStatus == null ? "-" : fromStatus, toStatus, tsMs);
    }

    /** 取某订单的事件流快照（按发生顺序）。 */
    public List<TaskLogEntry> entriesOf(String orderId) {
        List<TaskLogEntry> entries = entriesByOrder.get(orderId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** 已记录事件的订单数。 */
    public int trackedOrderCount() {
        return entriesByOrder.size();
    }

    /**
     * 一条 {@code task_log} 记录。
     *
     * @param orderId    订单标识
     * @param opType     操作类型
     * @param fromStatus 变更前状态（首次记录为 null）
     * @param toStatus   变更后状态
     * @param tsMs       时间戳，Unix 毫秒
     */
    public record TaskLogEntry(
            String orderId,
            String opType,
            String fromStatus,
            String toStatus,
            long tsMs) {
    }
}
