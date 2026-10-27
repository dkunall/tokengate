package com.tokengate.gateway.domain.ratelimit;

import java.time.Duration;

/**
 * Lazy-refill token bucket: no timer, the refill since the last call is added when someone asks. This is the
 * in-process version and the reference the Redis Lua script (redis/token_bucket.lua) is tested against; the
 * arithmetic is the same.
 */
public final class TokenBucket {

    private final RateLimitRule rule;
    private double tokens;
    private long lastNanos;

    public TokenBucket(RateLimitRule rule, long nowNanos) {
        this.rule = rule;
        this.tokens = rule.burst();
        this.lastNanos = nowNanos;
    }

    public synchronized RateLimitDecision tryConsume(long cost, long nowNanos) {
        double rate = rule.refillPerSecond();
        long elapsed = Math.max(0, nowNanos - lastNanos);
        tokens = Math.min(rule.burst(), tokens + elapsed / 1e9 * rate);
        lastNanos = nowNanos;
        boolean allowed = tokens >= cost;
        if (allowed) {
            tokens -= cost;
        }
        Duration retryAfter = allowed ? Duration.ZERO : seconds((cost - tokens) / rate);
        Duration reset = seconds((rule.burst() - tokens) / rate);
        return new RateLimitDecision(allowed, rule.burst(), (long) Math.floor(tokens), retryAfter, reset);
    }

    private static Duration seconds(double s) {
        return Duration.ofNanos((long) Math.ceil(s * 1e9));
    }
}
