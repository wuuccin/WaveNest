package com.waterlogistics.monitor.detect;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.monitor.alert.AlertRecorder;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M4 全域气象检测入口。
 *
 * <p>独立队列与 M2 同时消费同一气象主题，两个消费者不会竞争同一批消息。每次全域判档只产生
 * 一个检测结果，分发阶段再按去程中与末段交接中的订单逐条调用既有告警出口。</p>
 */
@Component
public class WeatherDetectionListener {

    private static final Logger log = LoggerFactory.getLogger(WeatherDetectionListener.class);

    private final WeatherViolationDetector detector;
    private final AlertRecorder recorder;
    private final OrderTaskMapper orderTaskMapper;

    /** 已收到当前气象周期的订单号，按无人机跟踪以便订单切换时复位旧事件。 */
    private final Map<String, String> notifiedOrderByDrone = new HashMap<>();

    /** 当前观测时刻已收到的格点风速最大值，等待下一时刻到达后统一判级。 */
    private long pendingObsTs = -1L;
    private double pendingMaxWindMs;
    private boolean hasPendingSample;

    public WeatherDetectionListener(WeatherViolationDetector detector, AlertRecorder recorder,
            OrderTaskMapper orderTaskMapper) {
        this.detector = detector;
        this.recorder = recorder;
        this.orderTaskMapper = orderTaskMapper;
    }

    /**
     * 消费一条风况并驱动全域检测。
     *
     * <p>M1 按位置与时刻发多条风况，同一观测时刻会到达多个格点。本方法先按 {@code obsTs}
     * 聚合格点并取风速最大值，收到下一个观测时刻时才对上一时刻判级一次，保证全域判级、
     * 迟滞计数与突变窗口都按观测时刻推进，而不是按消息条数推进。</p>
     *
     * <p>方法串行保护检测器窗口与订单跟踪表。单帧处理异常只记录日志，不中断后续气象消息。</p>
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m4-data-weather-queue:wavenest.m4.data-weather}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_WEATHER))
    public synchronized void onWeatherMessage(WeatherData message) {
        if (message == null) {
            log.warn("丢弃非法风况帧：消息为空");
            return;
        }
        if (!Double.isFinite(message.windSpeedMs()) || message.windSpeedMs() < 0.0
                || message.obsTs() <= 0L) {
            log.warn("丢弃非法风况帧：windSpeedMs={} obsTs={}",
                    message.windSpeedMs(), message.obsTs());
            return;
        }
        try {
            accumulate(message);
        } catch (RuntimeException e) {
            log.warn("气象超限检测失败，本次跳过：windSpeedMs={} obsTs={}",
                    message.windSpeedMs(), message.obsTs(), e);
        }
    }

    /**
     * 按观测时刻聚合格点，取风速最大值作为该时刻的全域代表值。
     *
     * <p>迟到的观测时刻直接丢弃：检测器的风速窗口要求时间单调，回退会破坏窗口语义。</p>
     */
    private void accumulate(WeatherData message) {
        long obsTs = message.obsTs();
        if (hasPendingSample && obsTs < pendingObsTs) {
            log.warn("丢弃迟到风况帧：obsTs={} 早于当前观测时刻={}", obsTs, pendingObsTs);
            return;
        }
        if (!hasPendingSample) {
            pendingObsTs = obsTs;
            pendingMaxWindMs = message.windSpeedMs();
            hasPendingSample = true;
            return;
        }
        if (obsTs == pendingObsTs) {
            pendingMaxWindMs = Math.max(pendingMaxWindMs, message.windSpeedMs());
            return;
        }
        long flushObsTs = pendingObsTs;
        double flushWindMs = pendingMaxWindMs;
        pendingObsTs = obsTs;
        pendingMaxWindMs = message.windSpeedMs();
        dispatch(detector.feed(flushWindMs, flushObsTs));
    }

    private void dispatch(DetectionResult result) {
        if (result.phase() == DetectionResult.Phase.CLEARED) {
            clearWeatherAlerts();
            return;
        }
        if (!result.hasAlert()) {
            return;
        }

        List<OrderTaskEntity> orders = activeOrders();
        Set<String> activeDrones = new HashSet<>();
        for (OrderTaskEntity order : orders) {
            if (order.getDroneId() != null && !order.getDroneId().isBlank()) {
                activeDrones.add(order.getDroneId());
            }
        }
        removeEndedOrders(activeDrones);

        for (OrderTaskEntity order : orders) {
            String droneId = order.getDroneId();
            String orderId = order.getOrderId();
            if (droneId == null || droneId.isBlank() || orderId == null || orderId.isBlank()) {
                log.warn("气象告警跳过标识不完整的订单：orderId={} droneId={}", orderId, droneId);
                continue;
            }
            String previousOrderId = notifiedOrderByDrone.get(droneId);
            if (previousOrderId != null && !previousOrderId.equals(orderId)) {
                recorder.resolve(droneId, AlertType.WEATHER_EXCEED);
            }
            recorder.record(result, droneId, orderId);
            notifiedOrderByDrone.put(droneId, orderId);
        }
    }

    private List<OrderTaskEntity> activeOrders() {
        return orderTaskMapper.selectList(new QueryWrapper<OrderTaskEntity>()
                .in("status", OrderStatus.OUTBOUND.getValue(), OrderStatus.HANDOVER.getValue()));
    }

    private void removeEndedOrders(Set<String> activeDrones) {
        Iterator<String> iterator = notifiedOrderByDrone.keySet().iterator();
        while (iterator.hasNext()) {
            String droneId = iterator.next();
            if (!activeDrones.contains(droneId)) {
                recorder.resolve(droneId, AlertType.WEATHER_EXCEED);
                iterator.remove();
            }
        }
    }

    private void clearWeatherAlerts() {
        for (String droneId : notifiedOrderByDrone.keySet()) {
            recorder.resolve(droneId, AlertType.WEATHER_EXCEED);
        }
        notifiedOrderByDrone.clear();
    }
}
