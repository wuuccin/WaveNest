package com.waterlogistics.app.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * MQ 统一 JSON 消息转换器装配（收口方维护，2026-10-17 补）。
 *
 * <p><b>为什么必须在装配层统一配</b>：本模块原先不引 {@code spring-boot-starter-amqp}，
 * {@code RabbitTemplate} 与 listener container 都走容器默认的
 * {@code SimpleMessageConverter}——它只支持 {@code String}/{@code byte[]}/{@code Serializable}。
 * 而《22》§1.2 约定的载荷全是 <b>record</b>（{@code AisMessage}／{@code UavTelemetry}／
 * {@code WeatherData} 等），<b>record 不实现 {@code Serializable}</b>，
 * 于是 {@code MqDataPublisher#send} 每拍都抛
 * {@code SimpleMessageConverter only supports String, byte[] and Serializable payloads}，
 * 而它只记 warn 不中断采集节拍（见该类 javadoc），<b>日志刷屏但作业照跑</b>，
 * 结果是 M1 三类数据一条都进不了 MQ，M2 预测／M4 监控全链路静默失效。
 *
 * <p><b>为什么用 {@code JacksonJsonMessageConverter} 而不是 {@code Jackson2JsonMessageConverter}</b>：
 * 本项目是 Spring Boot 4.1.x ＋ Jackson 3（包名 {@code tools.jackson}，见
 * {@code backend/README.md} §四「Spring Boot 4.x 三处差异」），
 * 对应的转换器是 {@code JacksonJsonMessageConverter}（构造入参为 Jackson 3 的 {@code JsonMapper}）；
 * 老名字那套基于 Jackson 2（{@code com.fasterxml}），本项目 classpath 上没有。
 *
 * <p><b>本类只定义一个 {@code MessageConverter} Bean</b>：Spring Boot 会自动把它
 * 应用到 {@code RabbitTemplate} 与 {@code SimpleRabbitListenerContainerFactory}，
 * 从而让 M1 发布侧与 M2／M4 消费侧共用同一套 JSON 口径，
 * 各业务模块无须各自配转换器（也<b>不许</b>各自配，避免两端口径分叉）。
 *
 * <p><b>已知取舍</b>：
 * <ul>
 *   <li><b>关闭 FAIL_ON_UNKNOWN_PROPERTIES</b>：契约对象按《22》演进，消费侧先收到新字段时
 *       不应整条消息反序列化失败（否则加一个字段就会打停全链路）。</li>
 *   <li><b>不开类型推断</b>（不设 inferred type id header）：消费侧靠
 *       {@code @RabbitListener} 方法签名确定目标类型，避免 {@code __TypeId__}
 *       带来的受信任包（trusted packages）限制与安全面。</li>
 * </ul>
 */
@Configuration
public class MqMessageConverterConfiguration {

    /**
     * 全项目唯一的 MQ JSON 消息转换器（发布侧序列化与消费侧反序列化共用）。
     */
    @Bean
    public MessageConverter mqJsonMessageConverter() {
        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return new JacksonJsonMessageConverter(mapper);
    }
}