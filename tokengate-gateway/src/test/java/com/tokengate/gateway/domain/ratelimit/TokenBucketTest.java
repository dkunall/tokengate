package com.tokengate.gateway.domain.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    void allowsBurstThenRejectsWithRetryAfter() {
        TokenBucket bucket = new TokenBucket(new RateLimitRule(60, 3), 0);
        assertThat(bucket.tryConsume(1, 0).remaining()).isEqualTo(2);
        bucket.tryConsume(1, 0);
        RateLimitDecision third = bucket.tryConsume(1, 0);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();

        RateLimitDecision denied = bucket.tryConsume(1, 0);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
        assertThat(denied.resetSeconds()).isEqualTo(3);
        assertThat(denied.limit()).isEqualTo(3);
    }

    @Test
    void refillsLazilyAndCapsAtBurst() {
        TokenBucket bucket = new TokenBucket(new RateLimitRule(60, 2), 0);
        bucket.tryConsume(2, 0);
        assertThat(bucket.tryConsume(1, SEC / 2).allowed()).isFalse();
        assertThat(bucket.tryConsume(1, SEC).allowed()).isTrue();
        RateLimitDecision later = bucket.tryConsume(1, 100 * SEC);
        assertThat(later.allowed()).isTrue();
        assertThat(later.remaining()).isEqualTo(1);
    }

    @Test
    void clockGoingBackwardsDoesNotMintTokens() {
        TokenBucket bucket = new TokenBucket(new RateLimitRule(60, 1), 10 * SEC);
        bucket.tryConsume(1, 10 * SEC);
        assertThat(bucket.tryConsume(1, 5 * SEC).allowed()).isFalse();
    }

    @Test
    void neverAdmitsMoreThanTheBurstUnderContention() throws Exception {
        TokenBucket bucket = new TokenBucket(new RateLimitRule(1, 50), 0);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[32];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < 10; j++) {
                    if (bucket.tryConsume(1, 0).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertThat(allowed.get()).isEqualTo(50);
    }
}
