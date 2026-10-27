package com.tokengate.metering.domain;

import com.tokengate.common.UsageEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UsageChargeTest {

    private final PriceBook prices = PriceBook.of(List.of(new PriceVersion("alpha", "alpha-small",
            new BigDecimal("1"), new BigDecimal("2"), Instant.parse("2026-01-01T00:00:00Z"))));

    @Test
    void pricesProviderCallsWithTheProviderModel() {
        UsageCharge c = UsageCharge.of(event("alpha", "alpha-small", UsageEvent.CacheOutcome.MISS,
                "2026-10-27T10:00:00Z"), prices);
        // 2 prompt tokens at 1/1k plus 1 completion token at 2/1k
        assertThat(c.cost()).isEqualByComparingTo("0.004");
        assertThat(c.priced()).isTrue();
        assertThat(c.provider()).isEqualTo("alpha");
    }

    @Test
    void cacheHitsAreFreeAndGroupedUnderCache() {
        UsageCharge c = UsageCharge.of(event(null, null, UsageEvent.CacheOutcome.SEMANTIC_HIT,
                "2026-10-27T10:00:00Z"), prices);
        assertThat(c.cost()).isZero();
        assertThat(c.cacheHit()).isTrue();
        assertThat(c.provider()).isEqualTo(UsageCharge.CACHE_PROVIDER);
        assertThat(c.priced()).isTrue();
    }

    @Test
    void unknownModelIsRecordedAsUnpricedAtZero() {
        UsageCharge c = UsageCharge.of(event("alpha", "new-model", UsageEvent.CacheOutcome.MISS,
                "2026-10-27T10:00:00Z"), prices);
        assertThat(c.priced()).isFalse();
        assertThat(c.cost()).isZero();
    }

    @Test
    void dayIsTheUtcDateNotTheServerZone() {
        UsageCharge c = UsageCharge.of(event("alpha", "alpha-small", UsageEvent.CacheOutcome.MISS,
                "2026-10-27T23:59:59Z"), prices);
        assertThat(c.day()).isEqualTo(LocalDate.of(2026, 10, 27));
        UsageCharge next = UsageCharge.of(event("alpha", "alpha-small", UsageEvent.CacheOutcome.MISS,
                "2026-10-28T00:00:00Z"), prices);
        assertThat(next.day()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    static UsageEvent event(String provider, String providerModel, UsageEvent.CacheOutcome cache, String at) {
        return new UsageEvent(1, UUID.randomUUID(), Instant.parse(at), "acme", "key-1", "req_1", "gpt-4o-mini",
                provider, providerModel, 2, 1, cache, false, 12, UsageEvent.Outcome.OK);
    }
}
