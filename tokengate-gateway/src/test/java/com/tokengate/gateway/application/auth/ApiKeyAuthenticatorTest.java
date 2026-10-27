package com.tokengate.gateway.application.auth;

import com.tokengate.gateway.application.admin.ApiKeyAdminService;
import com.tokengate.gateway.application.chat.Caller;
import com.tokengate.gateway.application.port.out.ApiKeyRepository;
import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.RoutingPolicy;
import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.PiiSettings;
import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticatorTest {

    private static final byte[] PEPPER = "unit-test-pepper-not-secret".getBytes(StandardCharsets.UTF_8);

    private final Keys keys = new Keys();
    private final Tenants tenants = new Tenants();
    private final ApiKeyAuthenticator auth = new ApiKeyAuthenticator(keys, tenants, PEPPER, Duration.ofMinutes(5));
    private final ApiKeyAdminService admin = new ApiKeyAdminService(keys, tenants, auth, PEPPER, Clock.systemUTC());

    ApiKeyAuthenticatorTest() {
        tenants.update(new Tenant(TenantId.of("acme"), "Acme", RateLimitRule.perMinute(60),
                new BudgetLimits(1000, Cost.ofUsd("1")), RoutingPolicy.COST, CachePolicy.defaults(),
                PiiSettings.defaults(), true, Instant.now()));
    }

    @Test
    void issued_key_authenticates_and_only_its_hash_is_stored() {
        ApiKeyAdminService.IssuedKey issued = admin.issue(TenantId.of("acme"), "ci");

        Optional<Caller> caller = auth.authenticate(issued.plaintext());

        assertThat(caller).map(Caller::tenantId).contains("acme");
        assertThat(keys.byId.get(issued.key().id()).secretHash()).doesNotContain(issued.plaintext().substring(12));
    }

    @Test
    void wrong_secret_with_a_valid_prefix_is_rejected() {
        String plaintext = admin.issue(TenantId.of("acme"), "ci").plaintext();
        String tampered = plaintext.substring(0, plaintext.length() - 1)
                + (plaintext.endsWith("a") ? "b" : "a");

        assertThat(auth.authenticate(tampered)).isEmpty();
        assertThat(auth.authenticate("not-a-key")).isEmpty();
        assertThat(auth.authenticate(null)).isEmpty();
    }

    @Test
    void lookups_are_cached_and_revocation_evicts_immediately_on_this_instance() {
        ApiKeyAdminService.IssuedKey issued = admin.issue(TenantId.of("acme"), "ci");
        auth.authenticate(issued.plaintext());
        auth.authenticate(issued.plaintext());
        assertThat(keys.prefixLookups).hasValue(1);

        admin.revoke(TenantId.of("acme"), issued.key().id());

        assertThat(auth.authenticate(issued.plaintext())).isEmpty();
    }

    @Test
    void inactive_tenant_is_rejected() {
        String plaintext = admin.issue(TenantId.of("acme"), "ci").plaintext();
        tenants.update(tenants.find(TenantId.of("acme")).orElseThrow().deactivated());
        auth.evictAll();

        assertThat(auth.authenticate(plaintext)).isEmpty();
    }

    private static final class Keys implements ApiKeyRepository {
        final Map<String, ApiKey> byId = new ConcurrentHashMap<>();
        final AtomicInteger prefixLookups = new AtomicInteger();

        @Override
        public void insert(ApiKey key) {
            byId.put(key.id(), key);
        }

        @Override
        public void update(ApiKey key) {
            byId.put(key.id(), key);
        }

        @Override
        public Optional<ApiKey> findByPrefix(String prefix) {
            prefixLookups.incrementAndGet();
            return byId.values().stream().filter(k -> k.prefix().equals(prefix)).findFirst();
        }

        @Override
        public Optional<ApiKey> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<ApiKey> findByTenant(TenantId tenant) {
            return byId.values().stream().filter(k -> k.tenantId().equals(tenant)).toList();
        }
    }

    private static final class Tenants implements TenantRepository {
        final Map<TenantId, Tenant> map = new ConcurrentHashMap<>();

        @Override
        public Optional<Tenant> find(TenantId id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public List<Tenant> findAll() {
            return List.copyOf(map.values());
        }

        @Override
        public boolean insert(Tenant t) {
            return map.putIfAbsent(t.id(), t) == null;
        }

        @Override
        public void update(Tenant t) {
            map.put(t.id(), t);
        }
    }
}
