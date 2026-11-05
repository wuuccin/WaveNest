package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
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
 *
 * <h3>11-05 序号 1 的失败口径（HIGH.20 / LOW-BUG.5 / MED-MAINT.9）</h3>
 * <p>改前只有一句 {@code catch (IllegalArgumentException)}：其余异常直接抛出监听方法。
 * 这不是「交给框架处理」——Spring AMQP 的 {@code defaultRequeueRejected} 缺省为
 * <b>{@code true}</b>，于是同一条毒帧会被无限重投，且每次重投都在打日志、占线程，
 * 整条 {@code data.ais} 队列在它后面排队。<b>一条脏消息能把全船队的预测停掉</b>，
 * 这是比丢一帧严重得多的事故。
 *
 * <p>故按「可预期 / 不可预期」二分处置：
 * <table border="1">
 *   <caption>异常分类与处置</caption>
 *   <tr><th>异常</th><th>含义</th><th>处置</th></tr>
 *   <tr>
 *     <td>{@link IllegalArgumentException}</td>
 *     <td><b>契约内</b>的脏观测：越界经纬度、负速度、非有限 COG、AIS 哨兵值。
 *         算法层两个实现都以此异常表达「这条观测不可用」（{@code ConstantVelocityPredictor.predict}
 *         与 {@link PrecisePredictionEngine} 的 {@code validate}），属预期分支而非故障</td>
 *     <td>丢弃本帧，{@code warn} ＋ <b>完整异常栈</b>；不重投（1 Hz 数据流，下一帧 1 s 后到）</td>
 *   </tr>
 *   <tr>
 *     <td>其余 {@link RuntimeException}</td>
 *     <td><b>不可预期</b>：NPE、状态机错乱、下游端口异常等。重投一万次也不会变好</td>
 *     <td>记 {@code error} ＋ 栈，并抛 {@link AmqpRejectAndDontRequeueException}
 *         让消息<b>一次进死信</b>（死信交换由序号 2 的张睿哲统一装配；未装配时等价于丢弃）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么不用「catch 全部再吞掉」</b>：那会把真实故障藏成「数据少了几帧」，
 * 出问题时无从定位；也<b>不能</b>改成「catch 全部再抛出」——那正是无限重投。
 * 两者的分水岭是「这条消息再处理一次会不会有不同结果」，本类按此分类。
 *
 * <p><b>为什么日志里要带栈</b>（MED-MAINT.9）：改前 {@code catch} 块只取
 * {@code e.getMessage()}，{@code IAE} 若来自非预期的深层代码，栈一丢就再也查不到出处。
 * 现在 waring 一并抛出 throwable，日志框架会输出完整栈；正常越界观测的 message
 * 本身已含越界值，栈只是「顺手带上」，不额外制造查询成本。
 *
 * <p><b>空消息防护</b>（LOW-BUG.5）：改前 {@code catch} 块里直接 {@code message.mmsi()}，
 * 而 {@code message == null} 恰好也会进 {@code catch}（{@code validate} 抛「观测不得为 null」），
 * 于是<b>异常处理路径自己抛 NPE</b>，把「丢一帧」升级成「消费中断」。
 * 现在所有日志字段都在 {@code try} 之前用空安全取值算好。
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
     * <p>单条异常不得中断后续消息；异常分类与处置见类注释。
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
        // 日志字段先算好：message 为 null 时 catch 块不能再解引用它（LOW-BUG.5）
        String mmsi = message == null ? null : message.mmsi();
        double lon = message == null ? Double.NaN : message.lon();
        double lat = message == null ? Double.NaN : message.lat();
        double sog = message == null ? Double.NaN : message.sog();
        long ts = message == null ? 0L : message.ts();

        try {
            predictionService.refresh(message);
        } catch (IllegalArgumentException e) {
            // 契约内的脏观测：丢弃本帧即可（1 Hz，下一帧 1 s 后到），重投没有意义
            log.warn("丢弃非法 AIS 观测：mmsi={} lon={} lat={} sog={} ts={} 原因={}",
                    mmsi, lon, lat, sog, ts, e.getMessage(), e);
        } catch (RuntimeException e) {
            // 不可预期故障：一次进死信，绝不重投（重投只会把队列堵死）
            log.error("AIS 观测处理异常，转死信不再重投：mmsi={} ts={}", mmsi, ts, e);
            throw new AmqpRejectAndDontRequeueException("AIS 观测处理异常，转死信", e);
        }
    }
}
