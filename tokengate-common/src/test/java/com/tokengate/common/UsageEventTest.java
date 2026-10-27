package com.tokengate.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageEventTest {

    private static UsageEvent event(long prompt, long completion, UsageEvent.CacheOutcome cache) {
        return new UsageEvent(1, UUID.randomUUID(), Instant.EPOCH, "t1", "k1", "r1", "tg-small", "mock-alpha",
                "alpha-small", prompt, completion, cache, false, 12, UsageEvent.Outcome.OK);
    }

    @Test
    void totalsAndCacheFlag() {
        assertThat(event(10, 5, UsageEvent.CacheOutcome.MISS).totalTokens()).isEqualTo(15);
        assertThat(event(10, 5, UsageEvent.CacheOutcome.MISS).servedFromCache()).isFalse();
        assertThat(event(10, 5, UsageEvent.CacheOutcome.SEMANTIC_HIT).servedFromCache()).isTrue();
        assertThat(event(10, 5, UsageEvent.CacheOutcome.BYPASS).servedFromCache()).isFalse();
    }

    @Test
    void rejectsNegativeTokens() {
        assertThatThrownBy(() -> event(-1, 0, UsageEvent.CacheOutcome.MISS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
