package com.tokengate.gateway.adapter.in.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tokengate.gateway.application.admin.ApiKeyAdminService;
import com.tokengate.gateway.application.admin.ProviderAdminService;
import com.tokengate.gateway.application.admin.TenantAdminService;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.Tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Response shapes for /admin. Domain objects never go on the wire directly; secret hashes stay inside. */
final class AdminViews {

    private AdminViews() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TenantView(String id, String name, long requestsPerMinute, long burst, long dailyTokens,
                      BigDecimal monthlyCostCapUsd, String routing, CacheView cache, PiiView pii, boolean active,
                      Instant createdAt) {

        static TenantView of(Tenant t) {
            return new TenantView(t.id().value(), t.name(), t.rateLimit().perMinute(), t.rateLimit().burst(),
                    t.budget().dailyTokens(), t.budget().monthlyCostCap().usd(), t.routing().name(),
                    new CacheView(t.cache().enabled(), t.cache().semanticEnabled(), t.cache().semanticThreshold(),
                            t.cache().ttl().toSeconds(), t.cache().cacheStreamed(),
                            t.cache().cacheNonDeterministic()),
                    new PiiView(t.pii().mode().name(), t.pii().customTerms().size()),
                    t.active(), t.createdAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record CacheView(boolean enabled, boolean semanticEnabled, double semanticThreshold, long ttlSeconds,
                     boolean cacheStreamed, boolean cacheNonDeterministic) {
    }

    // custom terms are often names of people or projects, so only the count is shown back
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PiiView(String mode, int customTermCount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KeyView(String id, String tenantId, String prefix, String name, Instant createdAt, Instant revokedAt,
                   String key, String note) {

        static KeyView of(ApiKey k) {
            return new KeyView(k.id(), k.tenantId().value(), k.prefix(), k.name(), k.createdAt(), k.revokedAt(),
                    null, null);
        }

        static KeyView issued(ApiKeyAdminService.IssuedKey issued) {
            ApiKey k = issued.key();
            return new KeyView(k.id(), k.tenantId().value(), k.prefix(), k.name(), k.createdAt(), null,
                    issued.plaintext(), "Store this key now. It is not shown again.");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record BudgetView(String tenantId, String day, long tokensUsedToday, long dailyTokenLimit, String month,
                      BigDecimal costThisMonthUsd, BigDecimal monthlyCostCapUsd) {

        static BudgetView of(TenantAdminService.BudgetStatus s) {
            return new BudgetView(s.tenant().id().value(), s.period().day(), s.usage().tokensToday(),
                    s.tenant().budget().dailyTokens(), s.period().month(), s.usage().costThisMonth().usd(),
                    s.tenant().budget().monthlyCostCap().usd());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ProviderView(String id, String type, int weight, int priority, boolean enabled, long timeoutMs,
                        Map<String, String> models, String breaker, double failureRate, Double latencyEwmaMs) {

        static ProviderView of(ProviderAdminService.ProviderStatus s) {
            ProviderDescriptor d = s.descriptor();
            Map<String, String> models = new TreeMap<>();
            d.models().forEach((logical, offer) -> models.put(logical, offer.nativeModel()));
            Double latency = s.health().latencyMillis().isPresent()
                    ? Math.round(s.health().latencyMillis().getAsDouble() * 10) / 10.0 : null;
            return new ProviderView(d.id().value(), d.type().name(), d.weight(), d.priority(), d.enabled(),
                    d.timeout().toMillis(), models, s.health().state().name(),
                    Math.round(s.health().failureRate() * 1000) / 1000.0, latency);
        }
    }

    record KeyRequest(String name) {
    }

    record ProviderPatch(Integer weight, Integer priority, Boolean enabled) {
    }

    static <T, R> List<R> map(List<T> in, java.util.function.Function<T, R> f) {
        return in.stream().map(f).toList();
    }
}
