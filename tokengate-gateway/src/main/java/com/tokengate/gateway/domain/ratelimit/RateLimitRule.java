package com.tokengate.gateway.domain.ratelimit;

/** Token bucket parameters. {@code perMinute} requests sustained, bursts up to {@code burst}. */
public record RateLimitRule(long perMinute, long burst) {

    public RateLimitRule {
        if (perMinute < 1 || burst < 1) {
            throw new IllegalArgumentException("rate limit must be positive");
        }
    }

    public static RateLimitRule perMinute(long rpm) {
        return new RateLimitRule(rpm, rpm);
    }

    public double refillPerSecond() {
        return perMinute / 60.0;
    }
}
