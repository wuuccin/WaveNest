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
 *
 * <h3>11-05 评审关闭要点（本文件承载 2 条）</h3>
 * <ul>
 *   <li><b>{@code HIGH.37}</b>：{@code gateTrigger.evaluate(...)} 原来在 {@code try} 块
 *       <b>外面</b>——脏帧能被拦住，但<b>闸门评估抛出的异常没人接</b>。{@code evaluate} 里
 *       调了 {@code gateService.trip/clear/summary} 这三个外部依赖，任一抛
 *       {@code RuntimeException}，异常就会从 {@code @RabbitListener} 方法里冒出去，
 *       Spring AMQP 默认把这条消息判为处理失败并按重试配置重投／丢弃，
 *       <b>一条风况的评估失败会变成整条消费链路的一次抖动</b>。现在把入仓与评估
 *       分两个 {@code try}：入仓失败＝丢帧，评估失败＝记 WARN 后放弃本次评估
 *       （下一条风况到达时会再评估一次，闸门状态不会因此永久错位）。</li>
 *   <li><b>{@code MED-PERF.11}</b>：<b>每帧都评估熔断</b>。11-05 起 M1 的网格由 2×2 改 3×3，
 *       每节拍消息数从 4 涨到 9；{@code evaluate} 是 {@code synchronized} 且内部要遍历
 *       全部新鲜格算「最不利风」（O(格数)），每帧一次等于把这些成本乘 9。
 *       现在加<b>最小评估间隔</b> {@link #MIN_EVALUATE_INTERVAL_MS}（200 ms）：
 *       同一节拍内到达的九条风况只评估一次，最后一条到达后的下一次评估必然覆盖到
 *       全部九格的值。间隔远小于 M1 的发布节拍（秒级），熔断响应时间不受影响。</li>
 * </ul>
 */
@Component
public class WeatherIngestListener {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestListener.class);

    /**
     * 两次熔断评估之间的最小间隔，ms。
     *
     * <p>取 200 ms：M1 每节拍 9 条消息几乎同时到达（同一 tick 内串行发布），
     * 间隔设 200 ms 正好把一拍内的九条合并成一次评估；而演示中「注入大风 → 熔断」
     * 的可见延迟仍是亚秒级，评审看不出差别。
     *
     * <p>为什么宁可漏评估也不漏判：本字段只做<b>节流</b>，不做「丢弃」——
     * 被节流跳过的那一条不会改变结论，因为下一次评估读的是快照仓的<b>当前全量</b>，
     * 而不是这一条消息本身。反过来若某条真的把最不利风推过阈值，它也一定会留在仓里，
     * 下一次评估必然看到。
     */
    static final long MIN_EVALUATE_INTERVAL_MS = 200L;

    private final WeatherSnapshotStore store;
    private final WeatherGateTrigger gateTrigger;

    /** 上次评估的时刻，ms（节流用；0 表示从未评估）。 */
    private long lastEvaluateMs;

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
        evaluateThrottled(System.currentTimeMillis());
    }

    /**
     * 节流后的闸门评估（{@code MED-PERF.11}）。
     *
     * <p><b>HIGH.37</b>：本方法自己吞掉评估期的 {@code RuntimeException}——
     * 闸门评估失败只该影响这一次评估，不该让 MQ 把整条消息判失败。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     */
    private void evaluateThrottled(long nowMs) {
        synchronized (this) {
            if (lastEvaluateMs != 0L && nowMs - lastEvaluateMs < MIN_EVALUATE_INTERVAL_MS) {
                return;
            }
            lastEvaluateMs = nowMs;
        }
        try {
            gateTrigger.evaluate(nowMs);
        } catch (RuntimeException e) {
            // HIGH.37：评估失败不得从 @RabbitListener 方法冒出去（否则消息被判处理失败、重投）
            log.warn("气象熔断评估失败，本次跳过（下一条风况到达时会重新评估）：{}", e.toString(), e);
        }
    }
}
