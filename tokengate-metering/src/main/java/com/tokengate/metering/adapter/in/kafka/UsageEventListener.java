package com.tokengate.metering.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.common.UsageEvent;
import com.tokengate.metering.application.UsageIngestion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.EnumMap;
import java.util.Map;

public final class UsageEventListener {

    private final UsageIngestion ingestion;
    private final ObjectMapper json;
    private final Map<UsageIngestion.Result, Counter> counters = new EnumMap<>(UsageIngestion.Result.class);

    public UsageEventListener(UsageIngestion ingestion, ObjectMapper json, MeterRegistry meters) {
        this.ingestion = ingestion;
        this.json = json;
        for (UsageIngestion.Result r : UsageIngestion.Result.values()) {
            counters.put(r, Counter.builder("metering.events")
                    .tag("result", r.name().toLowerCase())
                    .description("Usage events consumed, by outcome")
                    .register(meters));
        }
    }

    @KafkaListener(topics = UsageEvent.TOPIC, groupId = "${tokengate.metering.consumer-group}")
    public void onEvent(ConsumerRecord<String, String> record) {
        UsageEvent event;
        try {
            event = json.readValue(record.value(), UsageEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            throw new MalformedUsageEvent("unreadable usage event at " + record.topic() + "-" + record.partition()
                    + "@" + record.offset(), e);
        }
        counters.get(ingestion.ingest(event)).increment();
    }
}
