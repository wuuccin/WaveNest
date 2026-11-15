package com.waterlogistics.dispatch.prediction;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link RecomputeRequester} 的默认实现：<b>收到信号即真触发一版重算</b>（M2 · 卢肄文）。
 *
 * <h3>11-14 序号 2-6 · MEDIUM.2：重算搬离调用方线程</h3>
 * <p>此前 {@code requestRecompute} 在调用线程上同步跑完整重算。而它就在
 * {@link PredictionService#refresh} 的同步路径上——每条 AIS 观测都可能触发一次，
 * 于是 MQ 消费线程／HTTP 线程的时延被「重算耗时 × 触发次数」直接放大，多船同时跳变时串行叠加。
 * 现在改为<b>有界队列 ＋ 单线程 worker</b>：
 * <ul>
 *   <li>单线程而非线程池：重算会改同一份预测状态并递增版本，并发只会让版本先后变成竞态；</li>
 *   <li>队列有界（{@value #QUEUE_CAPACITY}）：宁可丢新信号，也不让积压无限增长——
 *       被丢的那条在下一拍 1 Hz 观测里还会再来；</li>
 *   <li>同船合并：同一条船已经有一次重算在排队或执行时，新的信号直接并掉，
 *       因为最终产出的都是「同一份最新状态的一版预测」；</li>
 *   <li>入口计数与留痕不变：{@link #receivedRequestCount()}／{@link #requestCount()}
 *       记的是「收到过多少条」，与是否被合并、丢弃无关，否则重算风暴在指标上看不见。</li>
 * </ul>
 * <p>测试与停机用 {@link #flush(long)} 等一个明确的「已处理完」点，不靠 sleep 猜时长。
 *
 * <p><b>F3「重算链接通」（10-23 任务清单 §三 卢肄文段）</b>：本类原先只打日志 ＋ 内存留痕，
 * 信号到舱门为止；现在把它接到 {@link PredictionService#recompute(String)}，
 * 让「预测发现前提变了 → 发重算信号 → 预测确实重算并广播新版本」这条闭环的前一半真正闭上。
 *
 * <p><b>类名保留</b>（当日任务明确要求）：本类同时承担两件事——留痕（{@link #recentRequests()}／
 * {@link #requestCount()}，供联调观察与单测断言）与触发。名字里的 Logging 是历史成因，
 * 不在当日改动范围内，改名会让 {@code dispatch} 侧的装配与既有测试全动一遍。
 *
 * <p><b>为什么用 setter 注入而不是构造器</b>：{@link PredictionService} 的构造器要本接口的 Bean，
 * 本类若在构造器里要 {@code PredictionService} 就成环，Spring Boot 4 默认禁环、启动即失败。
 * 故构造器保持无参（单测可直接 {@code new}），再由标注 {@code @Lazy} 的 setter 注入一个
 * 延迟代理——装配期只登记代理，调用期才解引用，环在时间维度上被拆开。
 *
 * <h3>三道防护（11-02 评审 MEDIUM.10／MEDIUM.2／LOW.6）</h3>
 * <ol>
 *   <li><b>重入闸门</b>：{@code recompute} 内部会 {@code publishEvent}，而 Spring 的
 *       {@code @EventListener} 默认在<b>发布线程同步执行</b>。任何监听方只要在回调里再摸一次
 *       预测（{@code refresh} 或再次请求重算），就会在同一线程上形成
 *       {@code requestRecompute → recompute → publishEvent → listener → requestRecompute}
 *       的无限递归，栈溢出前不会有人报错。{@link PredictionService} 关掉的是「自身回发信号」，
 *       挡不住监听方的回调，故必须在这里设闸。</li>
 *   <li><b>日志采样</b>：本方法在每船 1 Hz 的刷新路径上被调用，逐次 info 会在多船并发时
 *       把磁盘写满、也会把真正有用的告警淹掉。故「收到请求」按每 {@value #LOG_EVERY_N} 条
 *       记一次 info，其余降为 debug；「重算完成／失败」是低频且重要的事件，仍全量记。</li>
 *   <li><b>异常带栈</b>：{@code catch} 里必须带异常对象。只记 {@code getMessage()} 会丢掉
 *       类型与栈帧，生产上无法区分「引擎建网失败」「事件广播失败」「下游序列化失败」。</li>
 * </ol>
 *
 * <p><b>失败口径</b>：本方法在预测刷新热路径上被调用，下游不可用不得影响预测本身，
 * 故一切 {@link RuntimeException} 在此吞掉并记日志（接口约定即如此）。
 * <b>刻意不 catch {@link Error}</b>：OOM／StackOverflow 等错误表示 JVM 状态已不可信，
 * 吞掉只会让故障以更诡异的形式出现在别处；让它上抛、让线程死掉、让容器重启，是更安全的选择。
 * 这是接口「不得抛异常」的边界，写在这里以免后人以为是遗漏。
 */
@Component
public class LoggingRecomputeRequester implements RecomputeRequester {

    private static final Logger log = LoggerFactory.getLogger(LoggingRecomputeRequester.class);

    /** 内存留痕上限（环形，仅联调观察用）。 */
    static final int MAX_ENTRIES = 200;

    /** 「收到重算请求」日志的采样间隔：每 N 条记一次 info，其余 debug。 */
    static final int LOG_EVERY_N = 20;

    /**
     * 待处理重算队列容量（MEDIUM.2）。
     *
     * <p>取 256：单次重算是毫秒级，256 条足够吸收一分钟量级的抖动；再大只会把「系统已经
     * 跟不上」这件事藏得更久——队列越深，故障从「信号被丢」变成「重算结果严重滞后」，
     * 后者更难发现（版本号在涨，但涨的都是旧时刻的预测）。
     */
    static final int QUEUE_CAPACITY = 256;

    /**
     * 当前线程的重算深度（重入闸门）。
     *
     * <p>用 {@link ThreadLocal} 而不是全局原子标志：本类会被多船的输入线程并发调用，
     * 全局标志会把「A 船正在重算」误判成「B 船在递归」，直接吞掉正常的重算请求。
     * 递归是<b>同线程</b>现象（同步事件广播），按线程判才是对的。
     *
     * <p>用计数而不是布尔：正常路径上深度恒为 0/1，但万一将来出现「重算内部再做一次
     * 有意的重算」，计数能让日志说清是第几层，而布尔只能报「有递归」。
     */
    private static final ThreadLocal<Integer> RECOMPUTE_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 重算信号留痕（按发生顺序）。 */
    private final Deque<RecomputeRequest> recent = new ArrayDeque<>();

    /** 收到信号的总条数（日志采样与联调观察用；不受环形缓冲上限影响）。 */
    private final AtomicLong receivedCount = new AtomicLong();

    /** 因重入被断开而丢弃的条数（出现非 0 即说明有监听方在回调里摸预测）。 */
    private final AtomicLong recursionDropped = new AtomicLong();

    /**
     * 待处理重算队列（MEDIUM.2）。
     *
     * <p><b>为什么要移除调用方线程</b>：{@code requestRecompute} 处在
     * {@link PredictionService#refresh} 的同步路径上，每来一条 AIS 观测就可能走一次；
     * 而一次重算是「算法计算 ＋ 断言网 ＋ 事件广播」的完整过程。同步执行等于把
     * MQ 消费线程／HTTP 线程的时延按重算耗时线性拉长，多船同时跳变时还会串行叠加。
     *
     * <p><b>为什么是单线程而不是线程池</b>：重算改的是同一份预测状态并按版本广播，
     * 并发重算只会把「版本先后」变成竞态。单线程天然串行，顺序与信号到达顺序一致；
     * 队列有界，积压即丢新信号（丢掉的那条下一拍 1 Hz 观测还会再来，不会永久失效）。
     */
    private final BlockingQueue<RecomputeRequest> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * 已在队列或正在执行的船号 —— 同船合并的依据。
     *
     * <p>为什么按船号去重而不是按整条请求：同一船短时间内连发多次重算，最终只会产生
     * 同一份「最新状态的一版预测」，中间几次是纯浪费；不同船之间不能合并。
     */
    private final Set<String> inFlightMmsi = ConcurrentHashMap.newKeySet();

    /** 待处理（含正在执行）的条数，供 {@link #flush} 与优雅停机判定。 */
    private final AtomicInteger pending = new AtomicInteger();

    /** 被同船合并掉的条数。 */
    private final AtomicLong coalescedCount = new AtomicLong();

    /** 队列满或已停机的丢弃条数。 */
    private final AtomicLong droppedCount = new AtomicLong();

    /** 单线程执行器：只做「取一条、算一条」，异常一律在 {@link #execute} 内部吞掉。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prediction-recompute-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** 停机标志：停机后到达的信号只留痕不处理（避免 shutdown 后再往线程池里塞任务）。 */
    private volatile boolean running = true;

    /** 闭环回跳点；装配完成前为 {@code null}（此时退化为「只留痕」，不抛异常）。 */
    private volatile PredictionService predictionService;

    public LoggingRecomputeRequester() {
        worker.execute(this::runWorker);
    }

    /**
     * 注入重算落点。<b>刻意不加 {@code @Autowired(required = true)} 的构造器版本</b>，
     * 见类注释「为什么用 setter 注入」。
     */
    @Autowired
    void bindPredictionService(@Lazy PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    public void requestRecompute(RecomputeRequest request) {
        if (request == null) {
            log.warn("重算请求为空，忽略（调用方不应传 null）");
            return;
        }

        // ① 重入闸门：同线程再次进入说明是事件监听方回调引起的自激。判在入口，
        // 因为监听方回调就跑在执行业务的那个线程上——异步化之后是 worker 线程，
        // 而 worker 在执行期间会把深度置 1（见 execute）。
        int depth = RECOMPUTE_DEPTH.get();
        if (depth > 0) {
            long dropped = recursionDropped.incrementAndGet();
            log.warn("重算信号构成递归，已断开：mmsi={} 版本={} 深度={} 累计断开={}"
                            + "（检查 ship_traj 监听方是否在回调里再次触发预测）",
                    request.mmsi(), request.snapshotVersion(), depth, dropped);
            return;
        }

        // 计数与留痕在入口完成：信号「收到过」这件事与后面是否被合并、是否被丢弃无关，
        // 否则「重算风暴」在指标上会看不见。
        long sequence = receivedCount.incrementAndGet();
        record(request);
        logRequest(sequence, request);

        if (!running) {
            droppedCount.incrementAndGet();
            log.warn("重算器已停机，信号只留痕：mmsi={}", request.mmsi());
            return;
        }

        // ② 同船合并：队列里已有这船的重算，再排一条只会算出同一份结果
        if (!inFlightMmsi.add(request.mmsi())) {
            long coalesced = coalescedCount.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("重算信号与在途重算合并：mmsi={} 版本={} 累计合并={}",
                        request.mmsi(), request.snapshotVersion(), coalesced);
            }
            return;
        }

        pending.incrementAndGet();
        if (!queue.offer(request)) {
            // 队列满：撤回占位。丢掉的是「这一次信号」，不是「这船的重算」——
            // 下一拍观测若仍满足跳变条件会再来，且此前那版快照仍在库里可用。
            inFlightMmsi.remove(request.mmsi());
            pending.decrementAndGet();
            long dropped = droppedCount.incrementAndGet();
            log.warn("重算队列已满（上限 {}），本次信号丢弃：mmsi={} 累计丢弃={}",
                    QUEUE_CAPACITY, request.mmsi(), dropped);
        }
    }

    /**
     * 等待在途重算全部完成（单测与优雅停机用）。
     *
     * <p><b>为什么需要它</b>：异步化之后，「调用后立刻断言」不再成立。测试与停机都要一个
     * 明确的「现在已经处理完」的点，而不是 sleep 一个拍脑袋的时长。
     *
     * @param timeoutMs 最长等待，ms
     * @return 是否在超时前清空
     */
    public boolean flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (pending.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending.get() <= 0;
            }
        }
        return true;
    }

    /** 优雅停机：不再接新信号，中断在等队列的 worker。容器关闭时由 Spring 调用。 */
    @PreDestroy
    public void shutdown() {
        running = false;
        worker.shutdownNow();
        try {
            worker.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 被同船合并掉的条数（稳定运行时应远小于收到条数）。 */
    public long coalescedCount() {
        return coalescedCount.get();
    }

    /** 因队列满或停机被丢弃的条数。 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** worker 主循环：取一条、算一条；异常不外溢。 */
    private void runWorker() {
        while (running) {
            RecomputeRequest request;
            try {
                request = queue.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (request == null) {
                continue;
            }
            try {
                execute(request);
            } finally {
                inFlightMmsi.remove(request.mmsi());
                pending.decrementAndGet();
            }
        }
    }

    /**
     * 真正执行一次重算（在 worker 线程上）。
     *
     * <p>重入闸门在这里再设一次：{@code recompute} 内部会 {@code publishEvent}，而 Spring 的
     * {@code @EventListener} 默认在<b>发布线程同步执行</b>；监听方在回调里再请求一次重算时，
     * 那次请求就跑在本线程上，必须被入口的深度检查挡下，否则会无限入队。
     *
     * @param request 重算请求
     */
    private void execute(RecomputeRequest request) {
        int depth = RECOMPUTE_DEPTH.get();
        if (depth > 0) {
            long dropped = recursionDropped.incrementAndGet();
            log.warn("重算信号构成递归，已断开：mmsi={} 版本={} 深度={} 累计断开={}",
                    request.mmsi(), request.snapshotVersion(), depth, dropped);
            return;
        }
        RECOMPUTE_DEPTH.set(depth + 1);
        try {
            doRequestRecompute(request);
        } finally {
            int remaining = RECOMPUTE_DEPTH.get() - 1;
            if (remaining <= 0) {
                // 必须 remove：线程池里的线程会被复用，残留值会让下一条请求被误判为递归
                RECOMPUTE_DEPTH.remove();
            } else {
                RECOMPUTE_DEPTH.set(remaining);
            }
        }
    }

    /** 日志采样：首条与每 N 条记 info，其余 debug。 */
    private void logRequest(long sequence, RecomputeRequest request) {
        if (sequence == 1L || sequence % LOG_EVERY_N == 0L) {
            log.info("重算请求（第 {} 条）：mmsi={} 版本={} 航向 {}° → {}°（变化 {}°，原因={}）",
                    sequence, request.mmsi(), request.snapshotVersion(),
                    String.format("%.1f", request.previousCourseDeg()),
                    String.format("%.1f", request.currentCourseDeg()),
                    String.format("%.1f", request.courseDeltaDeg()),
                    request.triggerReason());
        } else if (log.isDebugEnabled()) {
            log.debug("重算请求（第 {} 条）：mmsi={} 版本={} 变化 {}°",
                    sequence, request.mmsi(), request.snapshotVersion(), request.courseDeltaDeg());
        }
    }

    private void doRequestRecompute(RecomputeRequest request) {
        PredictionService driver = this.predictionService;
        if (driver == null) {
            log.warn("重算落点未装配，信号仅留痕：mmsi={}", request.mmsi());
            return;
        }

        try {
            Optional<PredictionSnapshot> recomputed = driver.recompute(request.mmsi());
            if (recomputed.isPresent()) {
                PredictionSnapshot snapshot = recomputed.get();
                log.info("重算完成：mmsi={} 新版本={} 点数={} 有效期={}s，已广播 ship_traj",
                        snapshot.mmsi(), snapshot.version(),
                        snapshot.points().size(), snapshot.validitySec());
            } else {
                log.warn("重算跳过：mmsi={} 尚无可用观测", request.mmsi());
            }
        } catch (RuntimeException e) {
            // ③ 预测热路径：下游算不动不能反过来带停整条链；带异常对象以保留完整栈
            log.warn("重算失败，已忽略：mmsi={} 版本={}", request.mmsi(), request.snapshotVersion(), e);
        }
    }

    /** 已留痕的重算请求快照（按发生顺序）。 */
    public List<RecomputeRequest> recentRequests() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** 已留痕条数（环形缓冲内的，≤ {@value #MAX_ENTRIES}）。 */
    public int requestCount() {
        synchronized (recent) {
            return recent.size();
        }
    }

    /** 累计收到的重算信号条数（不受环形缓冲上限影响）。 */
    public long receivedRequestCount() {
        return receivedCount.get();
    }

    /** 因同线程递归被断开的条数；稳定运行时应恒为 0。 */
    public long recursionDroppedCount() {
        return recursionDropped.get();
    }

    private void record(RecomputeRequest request) {
        synchronized (recent) {
            if (recent.size() >= MAX_ENTRIES) {
                recent.removeFirst();
            }
            recent.addLast(request);
        }
    }
}
