package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    Optional<Tenant> find(TenantId id);

    List<Tenant> findAll();

    /** @return false if a tenant with this id already exists */
    boolean insert(Tenant tenant);

    void update(Tenant tenant);
}
