package com.tokengate.gateway.domain.tenant;

import java.time.Instant;
import java.util.Objects;

/** A stored key. Only the prefix (for lookup) and a keyed hash of the secret part are kept. */
public record ApiKey(String id, TenantId tenantId, String prefix, String secretHash, String name,
                     Instant createdAt, Instant revokedAt) {

    public ApiKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(secretHash, "secretHash");
    }

    public boolean active() {
        return revokedAt == null;
    }

    public ApiKey revoke(Instant at) {
        return active() ? new ApiKey(id, tenantId, prefix, secretHash, name, createdAt, at) : this;
    }
}
