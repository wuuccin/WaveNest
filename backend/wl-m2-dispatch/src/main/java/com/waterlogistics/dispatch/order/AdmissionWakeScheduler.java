package com.waterlogistics.dispatch.order;

import com.waterlogistics.dispatch.reschedule.RescheduleService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 延后单定时唤醒器（阶段一 26 · M2 · 卢肄文）。
 *
 * <p>接单判定出口为 {@code DEFER} 且给出可估计唤醒时刻（如「船驶入服务航段」的
 * {@code tEnter − wakeMargin}、数据不足的 {@code suspendRetrySec} 重评点）时，
 * 由本类注册一次性唤醒任务；到点把订单交回调度链
 * （{@link RescheduleService#reschedule}：重入队 → 组批 → 选机 → 会面 → 派发）。
 *
 * <p><b>为什么走 reschedule 而不是重跑接单判定</b>：与 {@code WeatherClearWaker}（闸门
 * 解除唤醒）同一语义——「已受理待唤醒」单的唤醒入口统一是重入队＋编排；接单判定是
 * <b>接单时点的一次性闸门</b>，唤醒后的可行性由调度链自身的闸门与候选判定兜住。
 * 两条唤醒链（定时＋闸门解除）共用同一出口，避免出现两套唤醒口径。
 *
 * <p><b>失败语义</b>：到点唤醒失败（订单已终止／被人工处理）只记警告，不重试、不抛出
 * ——唤醒器是尽力而为的助推，不是订单状态的权威；订单状态以 {@code order_task} 为准。
 */
@Service
public class AdmissionWakeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdmissionWakeScheduler.class);

    private final RescheduleService rescheduleService;

    private final ScheduledExecutorService executor;

    public AdmissionWakeScheduler(RescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
        this.executor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "admission-wake");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 注册一次到点唤醒。
     *
     * @param orderId  延后订单号
     * @param wakeAtMs 唤醒时刻，Unix 毫秒；已过去的时刻立即唤醒
     */
    public void scheduleWake(String orderId, long wakeAtMs) {
        long delayMs = Math.max(0L, wakeAtMs - System.currentTimeMillis());
        executor.schedule(() -> wake(orderId), delayMs, TimeUnit.MILLISECONDS);
        log.info("已注册延后单唤醒：orderId={} 唤醒时刻={} 延迟={}s",
                orderId, wakeAtMs, delayMs / 1000);
    }

    private void wake(String orderId) {
        try {
            rescheduleService.reschedule(orderId).ifPresentOrElse(
                    droneId -> log.info("延后单唤醒成功：orderId={} 新机={}", orderId, droneId),
                    () -> log.info("延后单唤醒后仍被闸门延后：orderId={}", orderId));
        } catch (Exception e) {
            log.warn("延后单唤醒失败（订单可能已终止或被人工处理）：orderId={} 原因={}",
                    orderId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
