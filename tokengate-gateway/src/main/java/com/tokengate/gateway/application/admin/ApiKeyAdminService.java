package com.tokengate.gateway.application.admin;

import com.tokengate.gateway.application.auth.ApiKeyAuthenticator;
import com.tokengate.gateway.application.error.NotFoundException;
import com.tokengate.gateway.application.port.out.ApiKeyRepository;
import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.ApiKeySecret;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class ApiKeyAdminService {

    private final ApiKeyRepository keys;
    private final TenantRepository tenants;
    private final ApiKeyAuthenticator authenticator;
    private final byte[] pepper;
    private final Clock clock;

    public ApiKeyAdminService(ApiKeyRepository keys, TenantRepository tenants, ApiKeyAuthenticator authenticator,
                              byte[] pepper, Clock clock) {
        this.keys = keys;
        this.tenants = tenants;
        this.authenticator = authenticator;
        this.pepper = pepper.clone();
        this.clock = clock;
    }

    /** The plaintext is returned exactly once; only its hash is stored. */
    public IssuedKey issue(TenantId tenantId, String name) {
        tenants.find(tenantId).orElseThrow(() -> new NotFoundException("tenant " + tenantId));
        ApiKeySecret.Issued issued = ApiKeySecret.generate();
        ApiKey key = new ApiKey(UUID.randomUUID().toString(), tenantId, issued.prefix(),
                ApiKeySecret.hash(issued.secret(), pepper), name, clock.instant(), null);
        keys.insert(key);
        return new IssuedKey(key, issued.plaintext());
    }

    public List<ApiKey> list(TenantId tenantId) {
        return keys.findByTenant(tenantId);
    }

    public ApiKey revoke(TenantId tenantId, String keyId) {
        ApiKey key = keys.findById(keyId)
                .filter(k -> k.tenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("api key " + keyId));
        ApiKey revoked = key.revoke(clock.instant());
        keys.update(revoked);
        authenticator.evict(key.prefix());
        return revoked;
    }

    public record IssuedKey(ApiKey key, String plaintext) {
    }
}
