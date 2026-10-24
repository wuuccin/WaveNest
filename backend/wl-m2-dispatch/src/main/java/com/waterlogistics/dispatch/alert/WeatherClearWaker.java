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

    private final DispatchGateService gateService;
    private final RescheduleService rescheduleService;
    private final OrderTaskMapper orderTaskMapper;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wavenest-alert-wake-poll");
        thread.setDaemon(true);
        return thread;
    });

    /** 上一轮闸门是否打开（边沿检测记忆位） */
    private volatile boolean lastOpen;

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
        lastOpen = gateService.isOpen();
        if (!pollEnabled) {
            log.info("气象解除积压唤醒轮询未启用（wavenest.mq.wake-poll-enabled=false）");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely,
                pollIntervalSec, pollIntervalSec, TimeUnit.SECONDS);
        log.info("气象解除积压唤醒轮询已启动：间隔 {}s，起始闸门状态={}",
                pollIntervalSec, lastOpen ? "打开" : "熔断");
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 轮询一拍：检测 {@code 关闭 → 打开} 边沿，命中即唤醒积压。
     * 公开供测试与运维手动触发（幂等，未命中边沿时无事发生）。
     */
    public void tick() {
        boolean open = gateService.isOpen();
        boolean wasOpen = lastOpen;
        lastOpen = open;
        if (!open || wasOpen) {
            return;
        }
        int woken = wakeBacklog();
        if (woken > 0) {
            log.info("闸门解除边沿命中：唤醒积压订单 {} 笔（已受理待唤醒 → 重入队并续编排）", woken);
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
     * @return 成功唤醒的笔数；单笔失败只记警告（自身事务回滚，不影响其余）
     */
    public int wakeBacklog() {
        List<OrderTaskEntity> backlog = orderTaskMapper.selectList(
                new LambdaQueryWrapper<OrderTaskEntity>()
                        .eq(OrderTaskEntity::getStatus, OrderStatus.ACCEPTED_WAIT_WAKE.getValue()));
        int woken = 0;
        for (OrderTaskEntity order : backlog) {
            try {
                rescheduleService.reschedule(order.getOrderId());
                woken++;
            } catch (Exception e) {
                log.warn("唤醒订单 {} 失败，跳过不影响其余：{}", order.getOrderId(), e.getMessage());
            }
        }
        return woken;
    }
}
