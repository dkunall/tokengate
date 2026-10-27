package com.tokengate.gateway.adapter.in.web.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.pii.PiiMode;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.RoutingPolicy;
import com.tokengate.gateway.domain.tenant.PiiSettings;
import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Used for both create and PATCH: on PATCH a null field means "leave it as it is". */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TenantRequest(
        String id,
        String name,
        Long requestsPerMinute,
        Long burst,
        Long dailyTokens,
        String monthlyCostCapUsd,
        String routing,
        Cache cache,
        Pii pii,
        Boolean active) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Cache(Boolean enabled, Boolean semanticEnabled, Double semanticThreshold, Long ttlSeconds,
                        Boolean cacheStreamed, Boolean cacheNonDeterministic) {
    }

    public record Pii(String mode, List<String> customTerms) {
    }

    Tenant create(Instant now) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        RateLimitRule defaultRate = RateLimitRule.perMinute(60);
        Tenant base = new Tenant(TenantId.of(id), name, defaultRate,
                new BudgetLimits(1_000_000, Cost.ofUsd("50")), RoutingPolicy.LATENCY, CachePolicy.defaults(),
                PiiSettings.defaults(), true, now);
        return applyTo(base);
    }

    Tenant applyTo(Tenant t) {
        Tenant out = t;
        if (name != null) {
            out = new Tenant(out.id(), name, out.rateLimit(), out.budget(), out.routing(), out.cache(), out.pii(),
                    out.active(), out.createdAt());
        }
        if (requestsPerMinute != null || burst != null) {
            long rpm = requestsPerMinute != null ? requestsPerMinute : out.rateLimit().perMinute();
            long b = burst != null ? burst : (requestsPerMinute != null ? rpm : out.rateLimit().burst());
            out = out.withRateLimit(new RateLimitRule(rpm, b));
        }
        if (dailyTokens != null || monthlyCostCapUsd != null) {
            out = out.withBudget(new BudgetLimits(
                    dailyTokens != null ? dailyTokens : out.budget().dailyTokens(),
                    monthlyCostCapUsd != null ? Cost.ofUsd(monthlyCostCapUsd) : out.budget().monthlyCostCap()));
        }
        if (routing != null) {
            out = out.withRouting(RoutingPolicy.valueOf(routing.toUpperCase(Locale.ROOT)));
        }
        if (cache != null) {
            CachePolicy c = out.cache();
            out = out.withCache(new CachePolicy(
                    or(cache.enabled(), c.enabled()),
                    or(cache.semanticEnabled(), c.semanticEnabled()),
                    cache.semanticThreshold() != null ? cache.semanticThreshold() : c.semanticThreshold(),
                    cache.ttlSeconds() != null ? Duration.ofSeconds(cache.ttlSeconds()) : c.ttl(),
                    or(cache.cacheStreamed(), c.cacheStreamed()),
                    or(cache.cacheNonDeterministic(), c.cacheNonDeterministic())));
        }
        if (pii != null) {
            PiiSettings p = out.pii();
            out = out.withPii(new PiiSettings(
                    pii.mode() != null ? PiiMode.valueOf(pii.mode().toUpperCase(Locale.ROOT)) : p.mode(),
                    pii.customTerms() != null ? pii.customTerms() : p.customTerms()));
        }
        if (active != null && active != out.active()) {
            out = new Tenant(out.id(), out.name(), out.rateLimit(), out.budget(), out.routing(), out.cache(),
                    out.pii(), active, out.createdAt());
        }
        return out;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
