package com.tokengate.gateway.domain.tenant;

import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.RoutingPolicy;

import java.time.Instant;
import java.util.Objects;

public record Tenant(
        TenantId id,
        String name,
        RateLimitRule rateLimit,
        BudgetLimits budget,
        RoutingPolicy routing,
        CachePolicy cache,
        PiiSettings pii,
        boolean active,
        Instant createdAt) {

    public Tenant {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant name is required");
        }
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(pii, "pii");
    }

    public Tenant withRateLimit(RateLimitRule r) {
        return new Tenant(id, name, r, budget, routing, cache, pii, active, createdAt);
    }

    public Tenant withBudget(BudgetLimits b) {
        return new Tenant(id, name, rateLimit, b, routing, cache, pii, active, createdAt);
    }

    public Tenant withRouting(RoutingPolicy p) {
        return new Tenant(id, name, rateLimit, budget, p, cache, pii, active, createdAt);
    }

    public Tenant withCache(CachePolicy c) {
        return new Tenant(id, name, rateLimit, budget, routing, c, pii, active, createdAt);
    }

    public Tenant withPii(PiiSettings p) {
        return new Tenant(id, name, rateLimit, budget, routing, cache, p, active, createdAt);
    }

    public Tenant deactivated() {
        return new Tenant(id, name, rateLimit, budget, routing, cache, pii, false, createdAt);
    }
}
