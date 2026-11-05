package com.waterlogistics.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.DeclarableCustomizer;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link MqListenerResilienceConfiguration} 装配单测（11-05 序号 2 · 张睿哲）。
 *
 * <p>不连真实 broker，钉住三件装配事实：监听工厂不重投、两条源队列被挂上 DLX 参数、
 * 死信设施齐备且路由键对应源队列名。毒帧「进死信不重投」的端到端验证见
 * {@code CargoGateIntegrationTest}（真实 RabbitMQ）。</p>
 */
class MqListenerResilienceConfigurationTest {

    private MqListenerResilienceConfiguration config;

    @BeforeEach
    void setUp() {
        config = new MqListenerResilienceConfiguration();
        ReflectionTestUtils.setField(config, "aisQueueName", "wavenest.m2.data-ais");
        ReflectionTestUtils.setField(config, "weatherQueueName", "wavenest.m2.data-weather");
    }

    @Test
    @DisplayName("监听容器工厂：defaultRequeueRejected=false（毒帧不重投）")
    void factory_disablesRequeueRejected() {
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        // configure 只装配属性不建连接；ConnectionFactory 是接口，JDK 代理即可满足非空校验
        ConnectionFactory connectionFactory = (ConnectionFactory) java.lang.reflect.Proxy.newProxyInstance(
                ConnectionFactory.class.getClassLoader(), new Class<?>[]{ConnectionFactory.class},
                (proxy, method, args) -> null);
        SimpleRabbitListenerContainerFactory factory =
                config.rabbitListenerContainerFactory(configurer, connectionFactory);

        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(factory, "defaultRequeueRejected"),
                "defaultRequeueRejected 必须为 false，否则毒帧无限重投");
    }

    @Test
    @DisplayName("DeclarableCustomizer：AIS/气象两队列挂 DLX 参数，其他队列不动")
    void customizer_attachesDlxOnlyToSourceQueues() {
        DeclarableCustomizer customizer = config.deadLetterCustomizer();

        Queue ais = (Queue) customizer.apply(new Queue("wavenest.m2.data-ais", true));
        assertEquals(MqListenerResilienceConfiguration.DLX,
                ais.getArguments().get("x-dead-letter-exchange"));
        assertEquals("wavenest.m2.data-ais",
                ais.getArguments().get("x-dead-letter-routing-key"));

        Queue weather = (Queue) customizer.apply(new Queue("wavenest.m2.data-weather", true));
        assertEquals(MqListenerResilienceConfiguration.DLX,
                weather.getArguments().get("x-dead-letter-exchange"));

        Queue other = (Queue) customizer.apply(new Queue("wavenest.m2.delivery-receipt", true));
        assertTrue(other.getArguments() == null
                        || !other.getArguments().containsKey("x-dead-letter-exchange"),
                "非目标队列不得挂死信参数（本次只覆盖 AIS 与气象）");

        TopicExchange exchange = new TopicExchange("wavenest.data");
        assertTrue(customizer.apply(exchange) == exchange, "交换器声明原样放行");
    }

    @Test
    @DisplayName("死信设施：DLX 持久化 topic；两条 DLQ 持久化并按源队列名绑定")
    void deadLetterTopology_complete() {
        TopicExchange dlx = config.wavenestDeadLetterExchange();
        assertEquals(MqListenerResilienceConfiguration.DLX, dlx.getName());
        assertTrue(dlx.isDurable());

        Queue aisDlq = config.aisDeadLetterQueue();
        Queue weatherDlq = config.weatherDeadLetterQueue();
        assertEquals("wavenest.m2.data-ais.dlq", aisDlq.getName());
        assertEquals("wavenest.m2.data-weather.dlq", weatherDlq.getName());
        assertTrue(aisDlq.isDurable() && weatherDlq.isDurable());

        assertEquals("wavenest.m2.data-ais",
                config.aisDeadLetterBinding(aisDlq, dlx).getRoutingKey());
        assertEquals("wavenest.m2.data-weather",
                config.weatherDeadLetterBinding(weatherDlq, dlx).getRoutingKey());
    }
}
