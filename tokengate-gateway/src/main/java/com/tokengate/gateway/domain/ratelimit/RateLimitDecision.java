package com.tokengate.gateway.domain.ratelimit;

import java.time.Duration;

/**
 * Maps onto the response headers: RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset (seconds until the
 * bucket is full again) and, when denied, Retry-After (seconds until one token is back).
 */
public record RateLimitDecision(boolean allowed, long limit, long remaining, Duration retryAfter, Duration resetAfter) {

    public long retryAfterSeconds() {
        return Math.max(1, ceilSeconds(retryAfter));
    }

    public long resetSeconds() {
        return ceilSeconds(resetAfter);
    }

    private static long ceilSeconds(Duration d) {
        return Math.ceilDiv(d.toMillis(), 1000);
    }
}
