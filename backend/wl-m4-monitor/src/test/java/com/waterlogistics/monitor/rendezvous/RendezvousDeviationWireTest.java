package com.waterlogistics.monitor.rendezvous;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.waterlogistics.contract.mq.RendezvousDeviationMessage;
import com.waterlogistics.contract.rest.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;

class RendezvousDeviationWireTest {
    private static final class CapturingRabbit extends RabbitTemplate {
        String routingKey;
        Object message;

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message) {
            this.routingKey = routingKey;
            this.message = message;
        }
    }

    @Test
    void publishedPayloadCanBeReadAsContractWithoutLegacyKeys() throws Exception {
        CapturingRabbit rabbit = new CapturingRabbit();
        RendezvousDeviation sample = new RendezvousDeviation(
                "sample-1", "drone-1", "order-1", new GeoPoint(113.2, 30.35),
                new GeoPoint(113.20045, 30.35), 1700000000000L, 1700000005000L,
                43.18, 5.0, "DJI-M350", "home-1", "calm", "0-5kn");
        new RendezvousDeviationPublisher(rabbit).publish(sample);

        assertEquals(RendezvousDeviationMessage.TOPIC, rabbit.routingKey);
        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(rabbit.message);
        JsonNode body = mapper.readTree(json);
        assertEquals(13, body.size());
        assertFalse(body.has("dSpace"));
        assertFalse(body.has("dTime"));
        assertFalse(body.has("nestId"));
        RendezvousDeviationMessage contract = mapper.readValue(json, RendezvousDeviationMessage.class);
        assertEquals(sample.dSpaceM(), contract.dSpaceM());
        assertEquals(sample.dTimeS(), contract.dTimeS());
        assertEquals(sample.homeNestId(), contract.homeNestId());
        assertEquals(mapper.valueToTree(sample), mapper.valueToTree(contract));
    }
}
