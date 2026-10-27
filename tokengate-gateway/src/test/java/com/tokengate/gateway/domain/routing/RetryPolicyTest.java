package com.tokengate.gateway.domain.routing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(50), Duration.ofMillis(800));

    @Test
    void onlyRetriesFailuresThatProvablyDidNotRun() {
        assertThat(policy.shouldRetry(FailureKind.CONNECT, 1)).isTrue();
        assertThat(policy.shouldRetry(FailureKind.RATE_LIMITED, 1)).isTrue();
        assertThat(policy.shouldRetry(FailureKind.UNAVAILABLE, 1)).isTrue();
        assertThat(policy.shouldRetry(FailureKind.UNAVAILABLE, 2)).isFalse();
        assertThat(policy.shouldRetry(FailureKind.TIMEOUT, 1)).isFalse();
        assertThat(policy.shouldRetry(FailureKind.SERVER_ERROR, 1)).isFalse();
        assertThat(policy.shouldRetry(FailureKind.BAD_REQUEST, 1)).isFalse();
        assertThat(FailureKind.TIMEOUT.failover()).isTrue();
        assertThat(FailureKind.BAD_REQUEST.failover()).isFalse();
    }

    @Test
    void mapsHttpStatuses() {
        assertThat(FailureKind.fromHttpStatus(429)).isEqualTo(FailureKind.RATE_LIMITED);
        assertThat(FailureKind.fromHttpStatus(503)).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(FailureKind.fromHttpStatus(500)).isEqualTo(FailureKind.SERVER_ERROR);
        assertThat(FailureKind.fromHttpStatus(401)).isEqualTo(FailureKind.AUTH);
        assertThat(FailureKind.fromHttpStatus(400)).isEqualTo(FailureKind.BAD_REQUEST);
    }

    @Test
    void fullJitterStaysWithinTheExponentialCeilingAndCap() {
        SplittableRandom random = new SplittableRandom(7);
        long max1 = 0;
        for (int i = 0; i < 2_000; i++) {
            long b1 = policy.backoff(1, null, random).toMillis();
            long b10 = policy.backoff(10, null, random).toMillis();
            assertThat(b1).isBetween(0L, 100L);
            assertThat(b10).isBetween(0L, 800L);
            max1 = Math.max(max1, b1);
        }
        assertThat(max1).isGreaterThan(80);
    }

    @Test
    void retryAfterIsHonouredUpToTheCap() {
        SplittableRandom random = new SplittableRandom(1);
        assertThat(policy.backoff(1, Duration.ofMillis(300), random)).isEqualTo(Duration.ofMillis(300));
        assertThat(policy.backoff(1, Duration.ofSeconds(30), random)).isEqualTo(Duration.ofMillis(800));
    }
}
