package com.waterlogistics.collector.publisher;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;
import com.waterlogistics.contract.mq.TopicName;

/**
 * 数据发布器接口（照 30 附录 B：接口不加 I 前缀）。
 *
 * <p>连接器只依赖本接口，不直接依赖 Spring AMQP——换传输实现（如演示期内存总线）下游零改动。
 */
public interface DataPublisher {

    void publishAis(AisMessage msg);

    void publishUavTelemetry(UavTelemetry msg);

    void publishWeather(WeatherData msg);
}
