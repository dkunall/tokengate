package com.tokengate.gateway.domain.routing;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter (sleep a uniform random time in [0, min(cap, base * 2^attempt)]).
 * Full jitter spreads a herd of retrying requests better than equal or no jitter; see the AWS architecture
 * blog post "Exponential Backoff And Jitter".
 */
public record RetryPolicy(int maxAttemptsPerProvider, Duration base, Duration cap) {

    public RetryPolicy {
        if (maxAttemptsPerProvider < 1) {
            throw new IllegalArgumentException("need at least one attempt");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(2, Duration.ofMillis(50), Duration.ofMillis(800));
    }

    public boolean shouldRetry(FailureKind kind, int attemptsSoFar) {
        return kind.retrySameProvider() && attemptsSoFar < maxAttemptsPerProvider;
    }

    /** attempt is 1 for the first retry. A provider's Retry-After wins if it is longer, capped at {@code cap}. */
    public Duration backoff(int attempt, Duration retryAfter, RandomGenerator random) {
        long ceiling = Math.min(cap.toMillis(), base.toMillis() << Math.min(attempt, 20));
        long jittered = ceiling <= 0 ? 0 : random.nextLong(ceiling + 1);
        if (retryAfter != null) {
            jittered = Math.max(jittered, Math.min(retryAfter.toMillis(), cap.toMillis()));
        }
        return Duration.ofMillis(jittered);
    }
}
