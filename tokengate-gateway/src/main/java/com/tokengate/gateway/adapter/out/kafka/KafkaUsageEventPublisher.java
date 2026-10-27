package com.tokengate.gateway.adapter.out.kafka;

import com.tokengate.gateway.application.port.out.UsageEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Blocks until the broker acks, so the outbox row is only marked published once Kafka has it. */
public class KafkaUsageEventPublisher implements UsageEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration ackTimeout;

    public KafkaUsageEventPublisher(KafkaTemplate<String, String> kafka, String topic, Duration ackTimeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    @Override
    public void publish(String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
