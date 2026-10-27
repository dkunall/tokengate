package com.tokengate.metering.domain;

import com.tokengate.common.UsageEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/** What a single usage event adds to its tenant's daily row. */
public record UsageCharge(String tenantId, LocalDate day, String model, String provider, long promptTokens,
                          long completionTokens, BigDecimal cost, boolean cacheHit, boolean priced) {

    /** Rows for cache hits are grouped under this provider name since no provider was called. */
    public static final String CACHE_PROVIDER = "cache";

    public static UsageCharge of(UsageEvent event, PriceBook prices) {
        LocalDate day = event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate();
        if (event.servedFromCache() || event.provider() == null) {
            return new UsageCharge(event.tenantId(), day, event.model(), CACHE_PROVIDER, event.promptTokens(),
                    event.completionTokens(), BigDecimal.ZERO.setScale(PriceVersion.COST_SCALE),
                    event.servedFromCache(), true);
        }
        Optional<PriceVersion> price = prices.priceAt(event.provider(), event.providerModel(), event.occurredAt());
        BigDecimal cost = price.map(p -> p.cost(event.promptTokens(), event.completionTokens()))
                .orElse(BigDecimal.ZERO.setScale(PriceVersion.COST_SCALE));
        return new UsageCharge(event.tenantId(), day, event.model(), event.provider(), event.promptTokens(),
                event.completionTokens(), cost, false, price.isPresent());
    }
}
