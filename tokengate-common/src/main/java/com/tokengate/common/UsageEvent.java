package com.tokengate.common;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One billable (or cache-served) gateway call. Published to {@link #TOPIC} keyed by tenant id, so all events
 * for a tenant land on one partition in order. Consumers must treat {@code eventId} as the idempotency key:
 * the outbox relay is at-least-once.
 */
public record UsageEvent(
        int schemaVersion,
        UUID eventId,
        Instant occurredAt,
        String tenantId,
        String apiKeyId,
        String requestId,
        String model,
        String provider,
        String providerModel,
        long promptTokens,
        long completionTokens,
        CacheOutcome cache,
        boolean streamed,
        long latencyMs,
        Outcome outcome) {

    public static final String TOPIC = "tokengate.usage.v1";
    public static final int CURRENT_SCHEMA = 1;

    public enum CacheOutcome { MISS, L1_HIT, EXACT_HIT, SEMANTIC_HIT, BYPASS }

    public enum Outcome { OK, CLIENT_CANCELLED, PROVIDER_FAILED_MIDSTREAM }

    public UsageEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(outcome, "outcome");
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /** Cache hits never reached a provider, so they are metered but billed at zero provider cost. */
    public boolean servedFromCache() {
        return cache == CacheOutcome.L1_HIT || cache == CacheOutcome.EXACT_HIT || cache == CacheOutcome.SEMANTIC_HIT;
    }
}
