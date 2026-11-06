package com.waterlogistics.app.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DeclarableCustomizer;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消费侧韧性装配（11-05 序号 2 · 张睿哲 · 队列资源与 MQ 评审批量）：
 * 毒帧「一次进死信、不重投」，一处覆盖 AIS 与气象两条消费队列。
 *
 * <h3>三件事</h3>
 * <ol>
 *   <li><b>default-requeue-rejected=false</b>：覆盖 Boot 默认的
 *       {@code rabbitListenerContainerFactory}，监听方法抛出非致命异常时
 *       拒绝且<b>不重投</b>（不配则默认 requeue，毒帧会无限打满消费循环）；</li>
 *   <li><b>死信设施</b>：{@code wavenest.dlx}（topic）＋ 每源队列一条 DLQ，
 *       绑定键＝源队列名（与下文 x-dead-letter-routing-key 对应）；</li>
 *   <li><b>DeclarableCustomizer 挂 DLX 参数</b>：AIS/气象两队列的声明在各自
 *       监听类的 {@code @QueueBinding} 里（他人独占文件，不改），此处统一给
 *       这两条队列的声明追加 {@code x-dead-letter-exchange} 与
 *       {@code x-dead-letter-routing-key}——拒绝不重投的消息由 broker 路由进 DLQ。</li>
 * </ol>
 *
 * <p><b>运维注意</b>：broker 上已存在的旧队列没有 DLX 参数，AMQP 不允许变更
 * 既有队列参数（PRECONDITION_FAILED）——首次部署本配置前需删除
 * {@code wavenest.m2.data-ais}／{@code wavenest.m2.data-weather} 两条队列让其重建
 * （开发环境直接 rabbitmq 卷重建）。</p>
 */
@Configuration
public class MqListenerResilienceConfiguration {

    /** 死信交换名（全项目共用，一处覆盖两条源队列） */
    public static final String DLX = "wavenest.dlx";

    @Value("${wavenest.mq.m2-data-ais-queue:wavenest.m2.data-ais}")
    private String aisQueueName;

    @Value("${wavenest.mq.m2-data-weather-queue:wavenest.m2.data-weather}")
    private String weatherQueueName;

    /**
     * 覆盖 Boot 自动装配的同名 Bean：其余配置（含 MessageConverter）由 configurer 原样装配，
     * 唯一改动是 {@code defaultRequeueRejected=false}——消费异常默认不再重投。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /** 死信交换（topic，持久化）。 */
    @Bean
    public TopicExchange wavenestDeadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    public Queue aisDeadLetterQueue() {
        return new Queue(aisQueueName + ".dlq", true);
    }

    @Bean
    public Queue weatherDeadLetterQueue() {
        return new Queue(weatherQueueName + ".dlq", true);
    }

    @Bean
    public Binding aisDeadLetterBinding(Queue aisDeadLetterQueue, TopicExchange wavenestDeadLetterExchange) {
        return BindingBuilder.bind(aisDeadLetterQueue).to(wavenestDeadLetterExchange).with(aisQueueName);
    }

    @Bean
    public Binding weatherDeadLetterBinding(Queue weatherDeadLetterQueue,
            TopicExchange wavenestDeadLetterExchange) {
        return BindingBuilder.bind(weatherDeadLetterQueue).to(wavenestDeadLetterExchange)
                .with(weatherQueueName);
    }

    /**
     * 给 AIS/气象两条源队列的声明追加死信参数（不动监听类的 {@code @QueueBinding}）。
     * RabbitAdmin 在声明前统一应用本定制器，故注解声明的队列同样生效。
     */
    @Bean
    public DeclarableCustomizer deadLetterCustomizer() {
        return declarable -> {
            if (declarable instanceof Queue queue
                    && (aisQueueName.equals(queue.getName()) || weatherQueueName.equals(queue.getName()))) {
                queue.addArgument("x-dead-letter-exchange", DLX);
                queue.addArgument("x-dead-letter-routing-key", queue.getName());
            }
            return declarable;
        };
    }
}
