package com.waterlogistics.dispatch.record;

import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 调度结果记录器（M2 · 卢肄文 · 模块二功能 11「调度结果记录与展示」）。
 *
 * <p>写 {@code task_log} 事件流（表结构见 {@code 37} §5.13，字段一字不改）：
 * {@code order_id}／{@code op_type}／{@code from_status}／{@code to_status}／{@code ts}。
 *
 * <p><b>记录内容（《10-16 当日任务速览》§3.3：「调度结果完整入事件流」）</b>：
 * 凡<b>调度决策的产出</b>都进这条流——订单接入、受理判定、派发、重算请求、
 * 状态流转等。M2 是唯一调度主控，故本类是 M2 调度结果的<b>留痕出口</b>。
 *
 * <p><b>与 {@code dispatch.queue} 的分工（避免重复记账）</b>：
 * 张睿哲的 {@code TaskQueueService} 负责「订单/任务状态机推进」并同步写 {@code task_log}；
 * 本类负责<b>其余调度结果</b>（如订单接入、重算请求）。两者写同一张表、用不同
 * {@code op_type} 区分，<b>不重复记同一件事</b>——状态流转一律由 {@code queue} 记。
 *
 * <p><b>落地方式</b>：内存留痕（供当日联调与单测）＋ 落库 {@code task_log}（经
 * {@link TaskLogMapper}，MyBatis-Plus）。落库失败<b>只告警不抛出</b>——留痕不应阻断调度主流程。
 *
 * <p>状态词一律用 {@code 23} 的中文状态词（如「待受理」「已受理待唤醒」），
 * 与 {@code order_task.status} 落库值一致；不得自造（{@code 23} §9 禁用表）。
 */
@Component
public class TaskLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(TaskLogRecorder.class);

    /** 事件流上限：仅内存副本用，防止长时间运行无限增长（落库那份不受此限）。 */
    private static final int MAX_ENTRIES = 5_000;

    /** 落库失败重试次数（含首次）；三次仍失败只告警，不阻断调度主流程。 */
    private static final int PERSIST_MAX_ATTEMPTS = 3;

    /** 重试退避基数（毫秒），按尝试次数递增。 */
    private static final long PERSIST_RETRY_BACKOFF_MS = 50L;
    private static final int MAX_TRACKED_ORDERS = 1_000;
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long WARN_INTERVAL_MS = 60_000;

    /** 落库出口；{@code null} 表示只落内存（不依赖 Spring/DB 的单测场景）。 */
    private final TaskLogMapper taskLogMapper;

    private final Map<String, Deque<TaskLogEntry>> entriesByOrder = new LinkedHashMap<>();
    private final AtomicLong lastPersistWarningMs = new AtomicLong(Long.MIN_VALUE);

    /** 供不依赖 Spring 与数据库的单测使用（只落内存）。 */
    public TaskLogRecorder() {
        this(null);
    }

    @Autowired
    public TaskLogRecorder(TaskLogMapper taskLogMapper) {
        this.taskLogMapper = taskLogMapper;
    }

    /**
     * 记录一次调度结果。
     *
     * @param orderId    订单标识
     * @param opType     操作类型，如「订单接入」「受理判定」「重算请求」
     * @param fromStatus 变更前状态（可为 null，表示首次记录）
     * @param toStatus   变更后状态
     * @param tsMs       发生时刻，Unix 毫秒
     */
    public void record(String orderId, String opType, String fromStatus, String toStatus, long tsMs) {
        if (orderId == null || orderId.isBlank()) {
            // 留痕不阻断调度主流程：订单号为空的事件流记 warn 后丢弃，不落内存、不落库
            // （task_log.order_id 是 NOT NULL，落库必然失败）。
            log.warn("task_log 留痕跳过：订单号为空（op={}）", opType);
            return;
        }
        requireText(opType, "opType");
        requireText(toStatus, "toStatus");
        TaskLogEntry entry = new TaskLogEntry(orderId, opType, fromStatus, toStatus, tsMs);

        synchronized (entriesByOrder) {
            Deque<TaskLogEntry> entries = entriesByOrder.get(orderId);
            if (entries == null) {
                if (entriesByOrder.size() >= MAX_TRACKED_ORDERS) {
                    entriesByOrder.remove(entriesByOrder.keySet().iterator().next());
                }
                entries = new ArrayDeque<>();
                entriesByOrder.put(orderId, entries);
            }
            if (entries.size() >= MAX_ENTRIES) {
                entries.removeFirst();
            }
            entries.addLast(entry);
        }

        log.info("task_log：orderId={} op={} {} -> {} ts={}",
                orderId, opType, fromStatus == null ? "-" : fromStatus, toStatus, tsMs);

        persist(entry);
    }

    /** 取某订单的事件流快照（按发生顺序）。 */
    public List<TaskLogEntry> entriesOf(String orderId) {
        requireText(orderId, "orderId");
        synchronized (entriesByOrder) {
            Deque<TaskLogEntry> entries = entriesByOrder.get(orderId);
            return entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** 已记录事件的订单数。 */
    public int trackedOrderCount() {
        synchronized (entriesByOrder) {
            return entriesByOrder.size();
        }
    }

    /**
     * 持久化到 {@code task_log}；失败重试 {@link #PERSIST_MAX_ATTEMPTS} 次，仍失败只告警，不影响调度主流程。
     *
     * <p><b>为什么重试而不是直接丢</b>：落库失败多因瞬时原因（连接抖动、死锁），
     * 重试即可成功；直接丢弃会让事件流缺口无法追认。
     *
     * <p><b>为什么仍在调度线程内同步做</b>：本类无事务，落库不参与调度事务；
     * 改成异步线程池会多出「事件已记但尚未落库」的窗口，进程退出即丢，得不偿失。
     * 退避为毫秒级，总耗时上限远小于一次调度拍频，不构成实质阻塞。
     */
    private void persist(TaskLogEntry entry) {
        if (taskLogMapper == null) {
            return;
        }
        Exception failure = null;
        for (int attempt = 1; attempt <= PERSIST_MAX_ATTEMPTS; attempt++) {
            try {
                taskLogMapper.insert(toEntity(entry));
                return;
            } catch (Exception e) {
                failure = e;
                if (attempt < PERSIST_MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        long now = System.currentTimeMillis();
        long previous = lastPersistWarningMs.get();
        if (previous == Long.MIN_VALUE || now - previous >= WARN_INTERVAL_MS) {
            if (lastPersistWarningMs.compareAndSet(previous, now)) {
                log.warn("task_log 落库失败（已重试 {} 次，不阻断主流程）：orderId={} op={}",
                        PERSIST_MAX_ATTEMPTS, entry.orderId(), entry.opType(), failure);
            }
        }
    }

    /** 事件流条目 → {@code task_log} 行。 */
    private static TaskLogEntity toEntity(TaskLogEntry entry) {
        TaskLogEntity entity = new TaskLogEntity();
        entity.setOrderId(entry.orderId());
        entity.setOpType(entry.opType());
        entity.setFromStatus(entry.fromStatus());
        entity.setToStatus(entry.toStatus());
        entity.setTs(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(entry.tsMs()), DB_ZONE));
        return entity;
    }

    /** 重试前的毫秒级退避；被打断时立即恢复中断标志，不吞掉中断。 */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(PERSIST_RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
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
