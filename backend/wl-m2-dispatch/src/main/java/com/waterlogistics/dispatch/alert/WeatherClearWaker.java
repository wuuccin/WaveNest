package com.waterlogistics.dispatch.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 气象解除积压唤醒器（F1 · 2026-10-23 分派表第 3 行）——
 * 「气象类解除 → 扫描待唤醒的积压订单，逐单重入队」。
 *
 * <h3>为什么是边沿轮询，不是订阅消息</h3>
 * <p>告警通道上<b>没有「解除」消息</b>：M4 {@code AlertRecorder} 对 CLEARED 只做
 * 内存去重复位、不发布事件；M2 侧真正执行解除的是 B2 的 {@code WeatherGateTrigger}
 * （调 {@code clear(AUTO)}），而 {@code DispatchGateService} 是纯内存状态机、无事件。
 * 在「不改既有类」的 F1 边界下，消费侧能观察的解除信号只有闸门状态本身——
 * 故以低频轮询检测 {@code 关闭 → 打开} 边沿，边沿即解除时刻。
 *
 * <h3>触发动作</h3>
 * <p>对每笔「已受理待唤醒」（B3 熔断延后语义）的积压订单调
 * {@link RescheduleService#reschedule}＝重入队 ＋ 派发编排——<b>只重入队不编排的话
 * 订单会停在「待调度」无人驱动</b>（主链触发器只挂在下单事件上），重入队与续链
 * 必须成对。单笔失败吞掉记警告、不影响其余订单（自身事务回滚，状态原地不动）。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li><b>边沿触发</b>：闸门持续打开期间不重复扫描——避免对「编排不了的单子」
 *       （如无船位快照）每 5 秒重放一轮「重入队→失败→回滚」，把 task_log 刷成噪音；</li>
 *   <li><b>只动自己的开关</b>：不碰闸门状态，不判气象（那是 B2 的职责）；</li>
 *   <li>轮询用自带单线程守护调度器（项目未开 {@code @EnableScheduling}，不引入新全局开关），
 *       可由 {@code wavenest.mq.wake-poll-enabled} 关闭。</li>
 * </ul>
 */
@Component
public class WeatherClearWaker {

    private static final Logger log = LoggerFactory.getLogger(WeatherClearWaker.class);

    /** 积压扫描单页容量（MEDIUM.1 性能：分页后单页上限，避免全量载入） */
    private static final int BACKLOG_PAGE_SIZE = 200;

    private final DispatchGateService gateService;
    private final RescheduleService rescheduleService;
    private final OrderTaskMapper orderTaskMapper;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wavenest-alert-wake-poll");
        thread.setDaemon(true);
        return thread;
    });

    /** 上一轮闸门是否打开（边沿检测记忆位；只有唤醒成功后才推进） */
    private final AtomicBoolean lastOpen = new AtomicBoolean(false);

    /** 边沿占位：同一时刻只允许一个线程执行积压唤醒 */
    private final AtomicBoolean waking = new AtomicBoolean(false);

    @Value("${wavenest.mq.wake-poll-enabled:true}")
    private boolean pollEnabled;

    @Value("${wavenest.mq.wake-poll-interval-sec:5}")
    private long pollIntervalSec;

    public WeatherClearWaker(DispatchGateService gateService,
            RescheduleService rescheduleService,
            OrderTaskMapper orderTaskMapper) {
        this.gateService = gateService;
        this.rescheduleService = rescheduleService;
        this.orderTaskMapper = orderTaskMapper;
    }

    @PostConstruct
    void start() {
        boolean initialOpen = gateService.isOpen();
        lastOpen.set(initialOpen);
        if (!pollEnabled) {
            log.info("气象解除积压唤醒轮询未启用（wavenest.mq.wake-poll-enabled=false）");
            return;
        }
        // LOW.1（缺陷）：配置为 0 或负数时 scheduleWithFixedDelay 抛 IllegalArgumentException，
        // 启动即失败；钳制为最小 1 秒并告警，不因一条坏配置拖垮整个应用
        long intervalSec = Math.max(1L, pollIntervalSec);
        if (pollIntervalSec < 1L) {
            log.warn("轮询间隔配置非法（{}s），已钳制为 {}s：wavenest.mq.wake-poll-interval-sec",
                    pollIntervalSec, intervalSec);
        }
        if (initialOpen) {
            // 起始即打开：启动前可能已积压一批订单，先补扫一轮，补扫完成再进边沿轮询
            lastOpen.set(false);
            tickSafely();
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely,
                intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("气象解除积压唤醒轮询已启动：间隔 {}s，起始闸门状态={}",
                intervalSec, lastOpen.get() ? "打开" : "熔断");
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 轮询一拍：检测 {@code 关闭 → 打开} 边沿，命中即唤醒积压。
     * 公开供测试与运维手动触发（幂等，未命中边沿时无事发生）。
     *
     * <p><b>开口状态只在唤醒成功后推进</b>：唤醒抛异常时记忆位回滚为「熔断」，
     * 下一拍还会命中同一边沿、积压可重试，不会因为一次失败丢掉整批订单。
     */
    public void tick() {
        if (!gateService.isOpen()) {
            lastOpen.set(false);
            return;
        }
        if (lastOpen.get()) {
            return;
        }
        if (!waking.compareAndSet(false, true)) {
            return;
        }
        try {
            // 双检：等占位期间别的线程可能已完成本轮唤醒
            if (lastOpen.get()) {
                return;
            }
            int woken = wakeBacklog();
            lastOpen.set(true);
            if (woken > 0) {
                log.info("闸门解除边沿命中：唤醒积压订单 {} 笔（已受理待唤醒 → 重入队并续编排）", woken);
            }
        } catch (RuntimeException e) {
            lastOpen.set(false);
            throw e;
        } finally {
            waking.set(false);
        }
    }

    /** 调度线程内兜底：任何异常只记日志，不让定时任务静默死亡。 */
    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("积压唤醒轮询异常（下一拍继续）：{}", e.getMessage());
        }
    }

    /**
     * 扫描「已受理待唤醒」积压订单并逐单重入队＋续编排。
     *
     * <p><b>MEDIUM.1（性能）</b>：按 {@code order_id} 升序 keyset 分页（每页 {@value #BACKLOG_PAGE_SIZE}），
     * 不再一次性把全部积压载入 JVM。keyset 游标只前进不回头，单笔失败（仍留在积压态）也不会造成死循环。
     *
     * @return 成功唤醒的笔数；单笔失败只记警告（自身事务回滚，不影响其余）
     */
    public int wakeBacklog() {
        int woken = 0;
        String cursor = null;
        while (true) {
            List<OrderTaskEntity> page = orderTaskMapper.selectList(
                    new LambdaQueryWrapper<OrderTaskEntity>()
                            .eq(OrderTaskEntity::getStatus, OrderStatus.ACCEPTED_WAIT_WAKE.getValue())
                            .gt(cursor != null, OrderTaskEntity::getOrderId, cursor)
                            .orderByAsc(OrderTaskEntity::getOrderId)
                            .last("LIMIT " + BACKLOG_PAGE_SIZE));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (OrderTaskEntity order : page) {
                try {
                    // 闸门仍熔断时 reschedule 返回空 Optional（延后），不得计入「成功唤醒」：延后不是派发成功
                    if (rescheduleService.reschedule(order.getOrderId()).isPresent()) {
                        woken++;
                    } else {
                        log.info("订单 {} 重派延后（闸门熔断），保持待唤醒", order.getOrderId());
                    }
                } catch (Exception e) {
                    log.warn("唤醒订单 {} 失败，跳过不影响其余：{}",
                            order.getOrderId(), e.getMessage());
                }
            }
            cursor = page.get(page.size() - 1).getOrderId();
            if (page.size() < BACKLOG_PAGE_SIZE || cursor == null) {
                break;
            }
        }
        return woken;
    }
}
