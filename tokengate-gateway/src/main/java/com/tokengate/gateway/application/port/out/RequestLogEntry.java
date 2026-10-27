package com.tokengate.gateway.application.port.out;

import com.tokengate.common.UsageEvent;

import java.time.Instant;

public record RequestLogEntry(
        String requestId,
        String tenantId,
        String apiKeyId,
        String model,
        String provider,
        String providerModel,
        long promptTokens,
        long completionTokens,
        long estimatedCostMicros,
        UsageEvent.CacheOutcome cache,
        boolean streamed,
        long latencyMs,
        UsageEvent.Outcome outcome,
        int attempts,
        int piiMasked,
        Instant createdAt) {
}
