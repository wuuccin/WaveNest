package com.waterlogistics.dispatch.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.reschedule.RescheduleService;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 货到巢关轮询器（11-05 序号 2 · 张睿哲）：等货订单的放行与锁超时处置。
 *
 * <h3>为什么是轮询，不是挂在 cargo-ready 端点上</h3>
 * <p>{@code POST /api/orders/{orderId}/cargo-ready} 及其 service 在 order/ 包
 * （卢肄文独占），本关不越界改他人文件；与「气象解除无主动事件 → 边沿轮询」
 * （{@code WeatherClearWaker}）同一先例——以低频轮询检测 goods 表的就绪边沿。
 * 轮询同时天然兜住「货先到」以外的全部到达路径（含直接改库/批量导入）。</p>
 *
 * <h3>每拍做什么</h3>
 * <p>扫描「已受理待唤醒 + wait_reason=WAIT_CARGO」的订单，逐笔：
 * <ol>
 *   <li>货物已就绪（已就绪 + location=货巢）→ {@link RescheduleService#reschedule}
 *       重入队并续编排；门关复用本单已持有的巢时隙锁（findActiveSlotByOrder），
 *       不会重复占位；</li>
 *   <li>货未就绪且锁龄（now − slot.t1）超过 {@code sys_param.cargoLockTimeoutSec}，
 *       或锁已失踪（被终态释放/人工清库）→ {@link TaskQueueService#markResourceWaiting}
 *       释锁并置 WAIT_RESOURCE（事件照常发布，告警链路由宋冰鑫序号 2 消费）；</li>
 *   <li>其余（货未就绪、锁未超时）→ 不动。</li>
 * </ol>
 * WAIT_RESOURCE 的订单本类<b>不自动重试</b>——资源释放时机不由本关掌握，
 * 交给告警/人工处置，避免对锁不到的巢每拍重放「重入队→门关→再延后」刷 task_log。</p>
 *
 * <p>纪律：自带单线程守护调度器（项目未开 {@code @EnableScheduling}）；
 * keyset 分页单页 200（同 WeatherClearWaker 的 MEDIUM.1 口径）；
 * 单笔失败只记警告，自身事务回滚，不影响其余订单。</p>
 */
@Component
public class CargoGateWaker {

    private static final Logger log = LoggerFactory.getLogger(CargoGateWaker.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 积压扫描单页容量（与 WeatherClearWaker 同口径） */
    private static final int BACKLOG_PAGE_SIZE = 200;

    private final OrderTaskMapper orderTaskMapper;
    private final CargoNestGate cargoNestGate;
    private final OccupancySlotService occupancySlotService;
    private final TaskQueueService taskQueueService;
    private final RescheduleService rescheduleService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wavenest-cargo-gate-poll");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${wavenest.m2.cargo-gate-poll-enabled:true}")
    private boolean pollEnabled;

    @Value("${wavenest.m2.cargo-gate-poll-interval-sec:2}")
    private long pollIntervalSec;

    public CargoGateWaker(OrderTaskMapper orderTaskMapper, CargoNestGate cargoNestGate,
            OccupancySlotService occupancySlotService, TaskQueueService taskQueueService,
            RescheduleService rescheduleService) {
        this.orderTaskMapper = orderTaskMapper;
        this.cargoNestGate = cargoNestGate;
        this.occupancySlotService = occupancySlotService;
        this.taskQueueService = taskQueueService;
        this.rescheduleService = rescheduleService;
    }

    @PostConstruct
    void start() {
        if (!pollEnabled) {
            log.info("货到巢关轮询未启用（wavenest.m2.cargo-gate-poll-enabled=false）");
            return;
        }
        long intervalSec = Math.max(1L, pollIntervalSec);
        if (pollIntervalSec < 1L) {
            log.warn("轮询间隔配置非法（{}s），已钳制为 {}s：wavenest.m2.cargo-gate-poll-interval-sec",
                    pollIntervalSec, intervalSec);
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("货到巢关轮询已启动：间隔 {}s，锁超时 {}s", intervalSec, cargoNestGate.lockTimeoutSec());
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** 调度线程内兜底：任何异常只记日志，不让定时任务静默死亡。 */
    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("货到巢关轮询异常（下一拍继续）：{}", e.getMessage());
        }
    }

    /**
     * 轮询一拍：放行货到订单、处置锁超时订单。
     * 公开供测试与运维手动触发（幂等）。
     *
     * @return 本拍放行（重新入队并续编排）的订单数
     */
    public int tick() {
        int released = 0;
        String cursor = null;
        long nowMs = System.currentTimeMillis();
        long timeoutMs = cargoNestGate.lockTimeoutSec() * 1000L;
        while (true) {
            List<OrderTaskEntity> page = orderTaskMapper.selectList(
                    new LambdaQueryWrapper<OrderTaskEntity>()
                            .eq(OrderTaskEntity::getStatus, OrderStatus.ACCEPTED_WAIT_WAKE.getValue())
                            .eq(OrderTaskEntity::getWaitReason, "WAIT_CARGO")
                            .gt(cursor != null, OrderTaskEntity::getOrderId, cursor)
                            .orderByAsc(OrderTaskEntity::getOrderId)
                            .last("LIMIT " + BACKLOG_PAGE_SIZE));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (OrderTaskEntity order : page) {
                try {
                    if (handleOne(order, nowMs, timeoutMs)) {
                        released++;
                    }
                } catch (Exception e) {
                    log.warn("货到巢关处理订单 {} 失败，跳过不影响其余：{}",
                            order.getOrderId(), e.getMessage());
                }
            }
            cursor = page.get(page.size() - 1).getOrderId();
            if (page.size() < BACKLOG_PAGE_SIZE || cursor == null) {
                break;
            }
        }
        return released;
    }

    /**
     * @return true=本单已放行（重入队续编排）
     */
    private boolean handleOne(OrderTaskEntity order, long nowMs, long timeoutMs) {
        String orderId = order.getOrderId();
        if (cargoNestGate.isCargoReady(orderId, order.getCargoNestId())) {
            rescheduleService.reschedule(orderId);
            log.info("货到巢关放行：orderId={} cargoNestId={}（货已就绪，重入队续编排）",
                    orderId, order.getCargoNestId());
            return true;
        }
        Optional<OccupancySlotEntity> slot = occupancySlotService.findActiveSlotByOrder(orderId);
        if (slot.isEmpty()) {
            log.info("货到巢关待货单锁失踪，转资源等待：orderId={}", orderId);
            taskQueueService.markResourceWaiting(orderId);
            return false;
        }
        long lockAcquiredMs = slot.get().getT1().atZone(DB_ZONE).toInstant().toEpochMilli();
        if (nowMs - lockAcquiredMs > timeoutMs) {
            log.info("货到巢关锁超时（锁龄 {}ms > {}ms），转资源等待：orderId={} slotId={}",
                    nowMs - lockAcquiredMs, timeoutMs, orderId, slot.get().getSlotId());
            taskQueueService.markResourceWaiting(orderId);
        }
        return false;
    }
}
