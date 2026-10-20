package com.waterlogistics.collector.support;

import com.waterlogistics.collector.publisher.MqDataPublisher;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.mq.WeatherData;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用发布器：把三类消息收进内存列表，替代真实 RabbitMQ。
 *
 * <p>连接器持有的是具体类型 {@link MqDataPublisher}，因此这里显式继承并覆写三个发布方法。
 */
public class RecordingPublisher extends MqDataPublisher {

    private final List<AisMessage> ais = new ArrayList<>();
    private final List<UavTelemetry> telemetry = new ArrayList<>();
    private final List<WeatherData> weather = new ArrayList<>();

    public RecordingPublisher() {
        super(null);
    }

    @Override
    public void publishAis(AisMessage msg) {
        ais.add(msg);
    }

    @Override
    public void publishUavTelemetry(UavTelemetry msg) {
        telemetry.add(msg);
    }

    @Override
    public void publishWeather(WeatherData msg) {
        weather.add(msg);
    }

    public List<AisMessage> ais() {
        return ais;
    }

    public List<UavTelemetry> telemetry() {
        return telemetry;
    }

    public List<WeatherData> weather() {
        return weather;
    }

    public void clear() {
        ais.clear();
        telemetry.clear();
        weather.clear();
    }
}
