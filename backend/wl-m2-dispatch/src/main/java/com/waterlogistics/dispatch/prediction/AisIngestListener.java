package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 增量接入 {@code data.ais} 并驱动预测刷新（M2 · 卢肄文）——预测刷新驱动链的<b>入端</b>。
 *
 * <p><b>拓扑口径（为什么由本类声明队列）</b>：{@code 22} §1.2 只规定<b>主题名</b>
 * （＝路由键），未规定交换器与队列。交换器名由 M1 在
 * {@code application-m1-sim.yml} 中定为 {@code wavenest.mq.exchange = wavenest.data}（topic 型）；
 * 队列与绑定属<b>消费侧</b>责任，不声明则监听无处可挂，故本类用 {@link QueueBinding} 一并声明：
 *
 * <pre>
 *   exchange: wavenest.data (topic)  --routing key: data.ais-->  queue: wavenest.m2.data-ais
 * </pre>
 *
 * <b>主题名取自 {@link TopicName#DATA_AIS} 常量</b>（编译期常量，可用于注解），
 * 队列名与交换器名均可由属性覆盖：
 * <pre>
 *   wavenest:
 *     mq:
 *       exchange: wavenest.data                    # 与 M1 保持一致
 *       m2-data-ais-queue: wavenest.m2.data-ais    # 消费侧队列名
 * </pre>
 *
 * <p><b>前置</b>：需要 RabbitMQ 在线（由 {@code docker compose up -d} 起四件套）。
 * 队列与绑定在首次连接时自动声明，无需手工建队列。
 *
 * <p><b>载荷转换</b>：按 {@link AisMessage} 接收，要求容器配置 JSON 消息转换器
 * （MQ 拓扑与序列化口径统一约定，M1 发布侧同为 JSON）。若上游发裸字符串而转换器未配，
 * 应在统一配置里补 {@code MessageConverter}，<b>不在本类里解析字符串</b>。
 */
@Component
public class AisIngestListener {

    private static final Logger log = LoggerFactory.getLogger(AisIngestListener.class);

    private final PredictionService predictionService;

    public AisIngestListener(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 消费一条 AIS 船位并刷新预测快照
     * （并触发重算请求判定，见 {@link PredictionService#refresh(AisMessage)}）。
     *
     * <p>单条异常不得中断后续消息：越界观测只记警告并丢弃（不建快照、版本号不递增），
     * 避免一条脏数据把整条链带停。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-data-ais-queue:wavenest.m2.data-ais}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_AIS))
    public void onAisMessage(AisMessage message) {
        try {
            predictionService.refresh(message);
        } catch (IllegalArgumentException e) {
            log.warn("丢弃越界 AIS 观测：mmsi={} lon={} lat={} sog={} 原因={}",
                    message.mmsi(), message.lon(), message.lat(), message.sog(), e.getMessage());
        }
    }
}
