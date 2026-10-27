package com.tokengate.gateway.application.outbox;

import com.tokengate.gateway.application.port.out.OutboxStore;
import com.tokengate.gateway.application.port.out.UsageEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves committed usage events from the outbox table to Kafka. At-least-once: a crash between the broker ack
 * and marking the row published sends the event again, which the metering consumer dedups by event id.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outbox;
    private final UsageEventPublisher publisher;
    private final int batchSize;

    public OutboxRelay(OutboxStore outbox, UsageEventPublisher publisher, int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /** @return events published in this pass */
    public int relayOnce() {
        try {
            return outbox.drain(batchSize, m -> publisher.publish(m.key(), m.payload()));
        } catch (RuntimeException e) {
            log.warn("outbox relay pass failed, will retry: {}", e.toString());
            return 0;
        }
    }
}
