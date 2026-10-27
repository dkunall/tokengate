package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.RequestLogEntry;
import com.tokengate.gateway.application.port.out.UsageRecorder;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.routing.Cost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class UsageAccounting {

    private static final Logger log = LoggerFactory.getLogger(UsageAccounting.class);

    private final UsageRecorder recorder;
    private final GatewayMetrics metrics;
    private final Clock clock;

    public UsageAccounting(UsageRecorder recorder, GatewayMetrics metrics, Clock clock) {
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The answer has already been produced, so a database failure here is logged and counted rather than
     * turned into an error for the caller. The log line carries the event so it can be replayed.
     */
    public void record(Usage u) {
        Instant now = clock.instant();
        UsageEvent event = new UsageEvent(UsageEvent.CURRENT_SCHEMA, UUID.randomUUID(), now, u.caller().tenantId(),
                u.caller().apiKeyId(), u.requestId(), u.model(), u.provider(), u.providerModel(),
                u.usage().promptTokens(), u.usage().completionTokens(), u.cache(), u.streamed(), u.latencyMs(),
                u.outcome());
        RequestLogEntry entry = new RequestLogEntry(u.requestId(), u.caller().tenantId(), u.caller().apiKeyId(),
                u.model(), u.provider(), u.providerModel(), u.usage().promptTokens(), u.usage().completionTokens(),
                u.cost().micros(), u.cache(), u.streamed(), u.latencyMs(), u.outcome(), u.attempts(), u.piiMasked(),
                now);
        if (u.provider() != null) {
            metrics.tokens(u.caller().tenantId(), u.provider(), u.usage().promptTokens(), u.usage().completionTokens());
            metrics.cost(u.caller().tenantId(), u.provider(), u.cost());
        }
        try {
            recorder.record(entry, event);
        } catch (RuntimeException e) {
            metrics.usageRecordFailed();
            log.error("could not record usage event {}: {}", event, e.toString());
        }
    }

    /** @param provider null when the answer came from the cache */
    public record Usage(Caller caller, String requestId, String model, String provider, String providerModel,
                        TokenUsage usage, Cost cost, UsageEvent.CacheOutcome cache, boolean streamed, long latencyMs,
                        UsageEvent.Outcome outcome, int attempts, int piiMasked) {
    }
}
