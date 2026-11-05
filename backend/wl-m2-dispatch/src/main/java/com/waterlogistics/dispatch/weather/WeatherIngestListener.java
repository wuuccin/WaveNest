package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.WeatherData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 接入 {@code data.weather}（M2 · 卢肄文 · 10-20 任务 **B2**）——M2 气象消费的<b>入端</b>。
 *
 * <h3>口径（为什么 M2 直接消费、不提升 {@code AlertEvent}）</h3>
 * <p>{@code 22} §1.2 把 {@code data.weather} 的消费方定为 **M2／M3／M4／M5**；
 * 10-20 任务清单 B2 明确「M2 直接消费，<b>不提升 {@code AlertEvent}</b>」。
 * 理由：M2 要的是「某位置某时刻的风」这个<b>原始量</b>（喂候选判定的最不利风、喂会面点求解，
 * 以及闸门熔断的风速比较），而 {@code alert} 事件是 M4 检测并<b>判级</b>后的结论——
 * 走告警通道会反过来变成「M2 消费 M4 的判定」，既绕远又容易两处判级不一致。
 * M4 仍按自己的职责独立消费 {@code data.weather} 做气象超限检测（{@code 34-M4} §3.4），
 * 两条消费路径互不替代。
 *
 * <h3>拓扑（消费侧责任）</h3>
 * <p>与 {@code AisIngestListener} 同一套写法：{@code 22} §1.2 只规定主题名（路由键），
 * 交换器名由 M1 定为 {@code wavenest.data}（topic），队列与绑定属消费侧责任：
 * <pre>
 *   exchange: wavenest.data (topic) --routing key: data.weather--> queue: wavenest.m2.data-weather
 * </pre>
 * 两个名字都可由属性覆盖：
 * <pre>
 *   wavenest:
 *     mq:
 *       exchange: wavenest.data                       # 与 M1 保持一致
 *       m2-data-weather-queue: wavenest.m2.data-weather
 * </pre>
 *
 * <h3>发布粒度（U-05 甲案，消费侧必须按此假设）</h3>
 * <p>一条即「某位置某时刻的风」，M1 <b>按位置与时刻发多条</b>；本监听器<b>逐条</b>收、
 * 按网格点归并进 {@link WeatherSnapshotStore}，不做「一条全局代表值」的假设。
 *
 * <p><b>单条异常不得中断后续消息</b>：坐标／风速非法的脏帧只记警告并丢弃
 * （不建快照、不动闸门），避免一条脏数据把 M2 的气象消费带停。
 */
@Component
public class WeatherIngestListener {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestListener.class);

    private final WeatherSnapshotStore store;
    private final WeatherGateTrigger gateTrigger;

    public WeatherIngestListener(WeatherSnapshotStore store, WeatherGateTrigger gateTrigger) {
        this.store = store;
        this.gateTrigger = gateTrigger;
    }

    /**
     * 消费一条风况：入仓 → 评估气象熔断。
     *
     * @param message 风况（JSON → {@link WeatherData}，由统一消息转换器反序列化）
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-data-weather-queue:wavenest.m2.data-weather}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_WEATHER))
    public void onWeatherMessage(WeatherData message) {
        try {
            store.accept(message);
        } catch (IllegalArgumentException e) {
            log.warn("丢弃非法风况帧：lon={} lat={} wind={} obsTs={} 原因={}",
                    message == null ? null : message.lon(),
                    message == null ? null : message.lat(),
                    message == null ? null : message.windSpeedMs(),
                    message == null ? null : message.obsTs(),
                    e.getMessage());
            return;
        }
        gateTrigger.evaluate(System.currentTimeMillis());
    }
}
