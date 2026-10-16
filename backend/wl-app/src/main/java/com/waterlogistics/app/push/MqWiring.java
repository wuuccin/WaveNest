package com.waterlogistics.app.push;

import com.waterlogistics.contract.mq.TopicName;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Shared Rabbit topology and JSON serialization for M1, M2 and M4. */
@Configuration
@EnableScheduling
class MqWiring {
    @Bean JacksonJsonMessageConverter rabbitMessageConverter() { return new JacksonJsonMessageConverter(); }
    @Bean TopicExchange wavenestExchange() { return new TopicExchange("wavenest.data", true, false); }
    @Bean Queue dataAisQueue() { return new Queue("wavenest.m2.data.ais", true); }
    @Bean Queue dataUavQueue() { return new Queue("wavenest.m4.data.uav.telemetry", true); }
    @Bean Queue taskPlanQueue() { return new Queue("wavenest.m4.task.plan", true); }
    @Bean Binding dataAisBinding(Queue dataAisQueue, TopicExchange wavenestExchange) {
        return BindingBuilder.bind(dataAisQueue).to(wavenestExchange).with(TopicName.DATA_AIS);
    }
    @Bean Binding dataUavBinding(Queue dataUavQueue, TopicExchange wavenestExchange) {
        return BindingBuilder.bind(dataUavQueue).to(wavenestExchange).with(TopicName.DATA_UAV_TELEMETRY);
    }
    @Bean Binding taskPlanBinding(Queue taskPlanQueue, TopicExchange wavenestExchange) {
        return BindingBuilder.bind(taskPlanQueue).to(wavenestExchange).with(TopicName.TASK_PLAN);
    }
}
