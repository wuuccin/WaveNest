package com.waterlogistics.dispatch.deviation;

import com.waterlogistics.contract.mq.RendezvousDeviationMessage;
import com.waterlogistics.contract.mq.TopicName;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 会面偏差回流消费方（M3/M4 → M2，《86-G组算法方案》§5、功能 19）。
 *
 * <p>主题 {@code rendezvous.deviation} 属<b>数据回流</b>前缀，不并入 {@code alert.*}；
 * 消费后按机型／归属机巢／风况段／船速段分层统计，不写告警、不改阈值、不推进订单状态。
 *
 * <p>与 M1 发布侧共用装配层唯一的 JSON 转换器，按方法签名反序列化
 * {@link RendezvousDeviationMessage}（13 字段），不设类型推断头。
 */
@Component
public class RendezvousDeviationListener {

    private static final Logger log = LoggerFactory.getLogger(RendezvousDeviationListener.class);

    private final RendezvousDeviationStats stats;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public RendezvousDeviationListener(RendezvousDeviationStats stats) {
        this.stats = stats;
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-deviation-queue:wavenest.m2.rendezvous-deviation}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC),
            key = TopicName.RENDEZVOUS_DEVIATION))
    public void onDeviation(RendezvousDeviationMessage message) {
        if (message == null || message.sampleId() == null || message.sampleId().isBlank()) {
            log.warn("忽略空会面偏差样本");
            return;
        }
        if (!seen.add(message.sampleId())) {
            log.debug("重复会面偏差样本，跳过：sampleId={}", message.sampleId());
            return;
        }
        try {
            stats.accept(message);
            log.info("会面偏差样本已回流：sampleId={} orderId={} dSpaceM={} dTimeS={} 累计={}",
                    message.sampleId(), message.orderId(), message.dSpaceM(), message.dTimeS(),
                    stats.sampleCount());
        } catch (RuntimeException e) {
            // 单条脏样本只丢弃本条，不得打停整个监听容器
            log.warn("会面偏差样本处理失败（只丢弃本条）：sampleId={} err={}",
                    message.sampleId(), e.toString());
            seen.remove(message.sampleId());
        }
    }
}
