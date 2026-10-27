package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {

    void insert(ApiKey key);

    void update(ApiKey key);

    Optional<ApiKey> findByPrefix(String prefix);

    Optional<ApiKey> findById(String id);

    List<ApiKey> findByTenant(TenantId tenantId);
}
