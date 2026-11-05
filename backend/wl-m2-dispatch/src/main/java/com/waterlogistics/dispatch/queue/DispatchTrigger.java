package com.waterlogistics.dispatch.queue;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.dispatch.order.OrderSubmittedEvent;
import com.waterlogistics.dispatch.plan.DispatchOrchestrator;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 主链触发器（B0 · 10-20 任务清单；C0 · 10-21 续接派发编排）。
 *
 * <p>下单 → 受理 → 组批 → 选机 → 会面 → 派发 的<b>自动推进器</b>：
 * 监听 {@link OrderSubmittedEvent}，把内存中的订单落到 {@code order_task}，
 * 再驱动 {@link TaskQueueService#accept}（待受理 → 待调度）→
 * {@link DispatchOrchestrator#orchestrate}（组批 → 选机 → 会面 → 派发），
 * 使订单自然走到「已派发」。
 *
 * <p><b>闸门联动（B3）</b>：{@code startDispatch} 内部已查 {@code DispatchGateService}，
 * 熔断时自动转「已受理待唤醒」，编排器检测到后即结束本轮（不选机、不求解），
 * 不在此处重复判定。
 *
 * <p><b>落库时补船位</b>：{@code ship_lon}/{@code ship_lat} 不由客户端提交
 * （{@code OrderSubmitRequest} javadoc：服务端自行掌握、写入前补），
 * 此处从 {@link PredictionService} 最新快照取当前船位写入——
 * 候选距离过滤与会面求解都以该船位为前提。
 */
@Component
public class DispatchTrigger {

    private static final Logger log = LoggerFactory.getLogger(DispatchTrigger.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final OrderTaskMapper orderTaskMapper;
    private final TaskQueueService taskQueueService;
    private final DispatchOrchestrator dispatchOrchestrator;
    private final PredictionService predictionService;
    private final TransactionTemplate transactionTemplate;

    public DispatchTrigger(OrderTaskMapper orderTaskMapper, TaskQueueService taskQueueService,
            DispatchOrchestrator dispatchOrchestrator, PredictionService predictionService,
            PlatformTransactionManager transactionManager) {
        this.orderTaskMapper = orderTaskMapper;
        this.taskQueueService = taskQueueService;
        this.dispatchOrchestrator = dispatchOrchestrator;
        this.predictionService = predictionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 订单受理事件处理：落库 → 受理 → 派发编排（组批 → 选机 → 会面 → 派发）。
     *
     * <p>先在短事务里落单并受理，再由编排器在自己的事务内派发。
     * 事件发布方（OrderAdmissionService）不依赖本监听器的返回值，
     * 失败仅记录日志，不阻断下单响应（下单已返回 accepted）。
     */
    @EventListener
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        if (event == null) {
            log.warn("忽略空的订单受理事件");
            return;
        }
        String orderId = event.orderId();
        try {
            if (orderTaskMapper.selectById(orderId) != null) {
                log.info("订单事件重复投递，跳过：orderId={}", orderId);
                return;
            }
            if (event.deferred()) {
                // 阶段一 26：接单判定为「延后待唤醒」的单子——落库即「已受理待唤醒」，
                // 不受理、不组批；唤醒由 AdmissionWakeScheduler（定时）或
                // WeatherClearWaker（闸门解除）推回调度链
                OrderTaskEntity deferred = new OrderTaskEntity();
                deferred.setOrderId(orderId);
                deferred.setShipMmsi(event.shipMmsi());
                deferred.setGoodsWeight(event.goodsWeightDecimal());
                deferred.setGoodsType(event.goodsType());
                if (event.expectedTimeMs() != null) {
                    deferred.setExpectedTime(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(event.expectedTimeMs()), DB_ZONE));
                }
                deferred.setEtaTs(toDbTime(event.etaTs()));
                deferred.setPriority(event.priority());
                deferred.setCreateTime(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.submitTsMs()), DB_ZONE));
                deferred.setStatus(OrderStatus.ACCEPTED_WAIT_WAKE.getValue());
                fillShipPosition(deferred);
                transactionTemplate.executeWithoutResult(status -> {
                    if (orderTaskMapper.selectById(orderId) == null) {
                        orderTaskMapper.insert(deferred);
                    }
                });
                log.info("延后单落库：orderId={} waitReason={} wakeEtaTs={}",
                        orderId, event.waitReason(), event.wakeEtaTs());
                return;
            }

            // 1. 落库 order_task（待受理）——OrderAdmissionService 只存内存，落库由 queue 负责
            OrderTaskEntity entity = new OrderTaskEntity();
            entity.setOrderId(orderId);
            entity.setShipMmsi(event.shipMmsi());
            entity.setGoodsWeight(event.goodsWeightDecimal());
            entity.setGoodsType(event.goodsType());
            if (event.expectedTimeMs() != null) {
                entity.setExpectedTime(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.expectedTimeMs()), DB_ZONE));
            }
            entity.setEtaTs(toDbTime(event.etaTs()));
            entity.setPriority(event.priority());
            entity.setCreateTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.submitTsMs()), DB_ZONE));
            entity.setStatus(OrderStatus.PENDING_ACCEPT.getValue());
            fillShipPosition(entity);
            transactionTemplate.executeWithoutResult(status -> {
                if (orderTaskMapper.selectById(orderId) != null) {
                    throw new IllegalStateException("订单事件重复投递：" + orderId);
                }
                orderTaskMapper.insert(entity);
                // 落库与受理是一笔短事务；编排另起事务，不持有下单写锁。
                taskQueueService.accept(entity);
            });

            // 3. 派发编排（C0）：组批 → 选机 → 会面 → 派发 → 回填会面点
            //    闸门熔断时编排器转「已受理待唤醒」并返回 null，非失败
            String droneId = dispatchOrchestrator.orchestrate(orderId);

            if (droneId != null) {
                log.info("主链触发完成：orderId={} 已派发至 droneId={}", orderId, droneId);
            } else {
                log.info("主链触发完成：orderId={} 闸门熔断延后（已受理待唤醒）", orderId);
            }
        } catch (Exception e) {
            log.error("主链触发失败：orderId={} err={}", orderId, e.getMessage(), e);
            // 事件由下单请求同步发布；失败留在日志中，由监控和重试链路处理，
            // 不把调度异常伪装成下单接口失败。
        }
    }

    /**
     * Unix 毫秒转库内 {@code LocalDateTime}（{@code Asia/Shanghai}）。
     *
     * @param tsMs Unix 毫秒；{@code null} 原样返回 {@code null}（未算出的 ETA 就该是 NULL）
     * @return 库内时刻，可空
     */
    private static LocalDateTime toDbTime(Long tsMs) {
        return tsMs == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), DB_ZONE);
    }

    /**
     * 落库前补「下单时目标船位」：取预测快照的当前点（轨迹首点，即最新观测位置）。
     * 该船尚无快照时留空——候选距离过滤与会面求解会因数据不足而拒绝，
     * 本方法不臆造船位（缺数据是「看不到船」，不是「船在原点」）。
     */
    private void fillShipPosition(OrderTaskEntity entity) {
        predictionService.snapshotOf(entity.getShipMmsi()).ifPresent(snapshot -> {
            if (!snapshot.points().isEmpty()) {
                ShipTrajPoint current = snapshot.points().get(0);
                if (Double.isFinite(current.lon()) && Double.isFinite(current.lat())
                        && current.lon() >= -180 && current.lon() <= 180
                        && current.lat() >= -90 && current.lat() <= 90) {
                    entity.setShipLon(BigDecimal.valueOf(current.lon()));
                    entity.setShipLat(BigDecimal.valueOf(current.lat()));
                } else {
                    log.warn("忽略非法预测船位：mmsi={} lon={} lat={}",
                            entity.getShipMmsi(), current.lon(), current.lat());
                }
            }
        });
    }
}
