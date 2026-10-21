package com.waterlogistics.monitor.ingest;

import com.waterlogistics.algo.measurement.TelemetryCleaner;
import com.waterlogistics.monitor.detect.DetectionOrchestrator;
import com.waterlogistics.monitor.detect.DetectionSample;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.UavTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M4 遥测消费入口（10-21 任务 C5：M4 检测集成触发）。
 *
 * <p>契约口径：{@code data.uav.telemetry} 消费方含 M4（《22》§1.2），
 * 本监听器是 M4 侧唯一的 {@code @RabbitListener}，补全「遥测 → 清洗 → 检测 → 告警」
 * 真实链路。触发点与张睿哲 C0 的派发动作对齐（订单派发后遥测帧开始流入）。</p>
 *
 * <p>拓扑（与 M2 气象监听同套写法，消费侧责任）：
 * <pre>
 *   exchange: wavenest.data (topic) --routing key: data.uav.telemetry--> queue: wavenest.m4.data-uav-telemetry
 * </pre>
 * 队列名与交换机名均可由属性覆盖：
 * <pre>
 *   wavenest:
 *     mq:
 *       exchange: wavenest.data
 *       m4-data-uav-telemetry-queue: wavenest.m4.data-uav-telemetry
 * </pre></p>
 *
 * <p>单条异常不中断后续消息：脏帧只记警告并丢弃，避免一条坏数据把检测链带停。</p>
 */
@Component
public class UavTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(UavTelemetryListener.class);

    private final TelemetryService telemetryService;
    private final DetectionOrchestrator detectionOrchestrator;

    public UavTelemetryListener(TelemetryService telemetryService,
                                DetectionOrchestrator detectionOrchestrator) {
        this.telemetryService = telemetryService;
        this.detectionOrchestrator = detectionOrchestrator;
    }

    /**
     * 消费一条遥测帧：清洗 → 检测 → 告警。
     *
     * @param message 遥测（JSON → {@link UavTelemetry}，由统一消息转换器反序列化）
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m4-data-uav-telemetry-queue:wavenest.m4.data-uav-telemetry}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_UAV_TELEMETRY))
    public void onTelemetryMessage(UavTelemetry message) {
        if (message == null || message.uavId() == null) {
            log.warn("丢弃非法遥测帧：message={}", message);
            return;
        }
        try {
            // 1. 清洗（M4 ingest 侧职责）
            var state = telemetryService.acceptState(message);

            // 2. 组装检测样本（missionActive 暂由遥测存在即视为在飞，
            //    后续与 C0 派发状态对齐后由任务状态机供值）
            DetectionSample sample = new DetectionSample(
                    message.uavId(),
                    null,                    // orderId：待与任务状态机对齐后注入
                    message.battery(),
                    null,                    // projection：待彭紫情 track 侧接入后注入
                    message.ts(),
                    true                     // missionActive：遥测存在即视为在飞
            );

            // 3. 驱动检测链
            detectionOrchestrator.onSample(sample);

        } catch (Exception e) {
            log.warn("遥测处理失败：uavId={} ts={} err={}",
                    message.uavId(), message.ts(), e.getMessage());
        }
    }
}
