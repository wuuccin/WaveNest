package com.waterlogistics.monitor.rendezvous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会面偏差回流 MQ 发送器（G 组功能 19，M3 → M2）。
 *
 * <p>16 号简化版：只发 MQ ＋ 内存留痕，不落库。
 * 主题：{@code rendezvous.deviation}。
 * 数据源：M2 的 {@code B_HandoverConfirmation}（经 M2 取，不新开主题）。
 *
 * <p>主题名暂以字面量携带：wl-contract 的 {@code TopicName} 常量增补属契约负责方（吴可欣）权限，
 * 待其加入后切换为常量引用（ TODO 知会契约方备案新主题）。
 *
 * <p>发送失败不阻断检测节拍，记警告留排障线索。
 */
@Component
public class RendezvousDeviationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RendezvousDeviationPublisher.class);

    /** 主题名（契约 TopicName 常量待契约方增补后切换） */
    private static final String TOPIC = "rendezvous.deviation";

    private final RabbitTemplate rabbit;

    /** 交换机名（与 M1 数据发布器同口径，demo 期用 topic 交换机） */
    @Value("${wavenest.mq.exchange:wavenest.data}")
    private String exchange;

    public RendezvousDeviationPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * 发送一条会面偏差样本。
     *
     * @param deviation 偏差样本（13 字段）
     */
    public void publish(RendezvousDeviation deviation) {
        try {
            rabbit.convertAndSend(exchange, TOPIC, deviation);
        } catch (Exception e) {
            log.warn("rendezvous.deviation publish failed: sampleId={}, droneId={}, orderId={}, err={}",
                    deviation.sampleId(), deviation.droneId(), deviation.orderId(), e.getMessage());
        }
    }
}
