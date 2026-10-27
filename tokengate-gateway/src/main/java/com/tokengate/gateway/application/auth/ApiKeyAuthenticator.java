package com.tokengate.gateway.application.auth;

import com.tokengate.gateway.application.chat.Caller;
import com.tokengate.gateway.application.port.out.ApiKeyRepository;
import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.domain.cache.LruTtlCache;
import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.ApiKeySecret;
import com.tokengate.gateway.domain.tenant.Tenant;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a presented key to its tenant. Lookups by prefix are cached briefly so the hot path does not hit
 * Postgres on every call; the hash comparison still runs every time. A revoked key or a changed tenant on
 * another gateway instance is picked up within the cache TTL.
 */
public final class ApiKeyAuthenticator {

    private final ApiKeyRepository keys;
    private final TenantRepository tenants;
    private final byte[] pepper;
    private final LruTtlCache<String, Resolved> cache;

    public ApiKeyAuthenticator(ApiKeyRepository keys, TenantRepository tenants, byte[] pepper, Duration cacheTtl) {
        this.keys = keys;
        this.tenants = tenants;
        this.pepper = pepper.clone();
        this.cache = LruTtlCache.create(10_000, cacheTtl);
    }

    public Optional<Caller> authenticate(String presented) {
        Optional<ApiKeySecret.Parsed> parsed = ApiKeySecret.parse(presented);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String prefix = parsed.get().prefix();
        Optional<Resolved> resolved = cache.get(prefix).or(() -> load(prefix));
        return resolved
                .filter(r -> r.key().active() && r.tenant().active())
                .filter(r -> ApiKeySecret.matches(parsed.get().secret(), r.key().secretHash(), pepper))
                .map(r -> new Caller(r.tenant(), r.key().id()));
    }

    public void evict(String prefix) {
        cache.remove(prefix);
    }

    public void evictAll() {
        cache.clear();
    }

    private Optional<Resolved> load(String prefix) {
        Optional<Resolved> r = keys.findByPrefix(prefix)
                .flatMap(k -> tenants.find(k.tenantId()).map(t -> new Resolved(k, t)));
        r.ifPresent(v -> cache.put(prefix, v));
        return r;
    }

    private record Resolved(ApiKey key, Tenant tenant) {
    }
}
