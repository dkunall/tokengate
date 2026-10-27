package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.RetryPolicy;

import java.time.Duration;

/**
 * @param defaultCompletionTokens used for budget and cost estimates when the request sets no max_tokens
 * @param hedgeAfter              if set, a second provider is started when the first has not answered by then
 */
public record GatewaySettings(
        long defaultCompletionTokens,
        RetryPolicy retry,
        CircuitBreaker.Config breaker,
        double latencyEwmaAlpha,
        Duration hedgeAfter,
        int l1Capacity,
        Duration l1Ttl) {

    public static GatewaySettings defaults() {
        return new GatewaySettings(256, RetryPolicy.defaults(), CircuitBreaker.Config.defaults(), 0.2, null, 10_000,
                Duration.ofSeconds(30));
    }

    public boolean hedging() {
        return hedgeAfter != null && !hedgeAfter.isZero() && !hedgeAfter.isNegative();
    }
}
