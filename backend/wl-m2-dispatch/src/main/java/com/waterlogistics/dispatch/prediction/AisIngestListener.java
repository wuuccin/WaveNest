package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.mq.AisMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 增量接入 {@code data.ais} 并驱动预测刷新（M2 · 卢肄文）。
 *
 * <p>主题名取自 {@link com.waterlogistics.contract.mq.TopicName#DATA_AIS}（{@code data.ais}，
 * 《22》§1.2 唯一权威）。<b>不得自造主题名</b>，注意不是 {@code data.ship_ais}。
 *
 * <p><b>★ 为什么默认关闭</b>：{@code 22} §1.2 只规定了<b>主题名</b>，没有规定
 * RabbitMQ 的交换器／队列命名与绑定关系（那是模块层的部署约定）。为避免「自造拓扑」，
 * 本监听只在显式配置了队列名时才启用：
 *
 * <pre>
 * wavenest:
 *   mq:
 *     data-ais-queue: &lt;M1 发布方声明的队列名&gt;
 * </pre>
 *
 * 未配置本属性时本 Bean 不注册，应用<b>不依赖 RabbitMQ 即可启动</b>（今天 RabbitMQ
 * 是否已起都不影响本模块编译与冒烟）。队列名确定后填上即可打通「仿真器 → 预测」。
 *
 * <p><b>载荷转换</b>：监听方法按 {@link AisMessage} 接收，要求容器配置 JSON 消息转换器
 * （由 MQ 拓扑与序列化口径统一定义）。若上游只发裸 JSON 字符串而转换器未配，
 * 该处会反序列化失败——此时应在统一配置里补 {@code MessageConverter}，而非在本类里解析字符串。
 */
@Component
@ConditionalOnProperty(prefix = "wavenest.mq", name = "data-ais-queue")
public class AisIngestListener {

    private static final Logger log = LoggerFactory.getLogger(AisIngestListener.class);

    private final PredictionService predictionService;

    public AisIngestListener(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 消费一条 AIS 船位并刷新预测快照。
     *
     * <p>单条异常不得中断后续消息：越界观测只记警告并丢弃（版本号不递增），
     * 避免一条脏数据把整条链带停。
     */
    @RabbitListener(queues = "${wavenest.mq.data-ais-queue}")
    public void onAisMessage(AisMessage message) {
        try {
            predictionService.refresh(message);
        } catch (IllegalArgumentException e) {
            log.warn("丢弃越界 AIS 观测：mmsi={} lon={} lat={} sog={} 原因={}",
                    message.mmsi(), message.lon(), message.lat(), message.sog(), e.getMessage());
        }
    }
}
