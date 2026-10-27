package com.tokengate.gateway.application.admin;

import com.tokengate.gateway.application.auth.ApiKeyAuthenticator;
import com.tokengate.gateway.application.error.ConflictException;
import com.tokengate.gateway.application.error.NotFoundException;
import com.tokengate.gateway.application.port.out.BudgetLedger;
import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.time.Clock;
import java.util.List;
import java.util.function.UnaryOperator;

public final class TenantAdminService {

    private final TenantRepository tenants;
    private final BudgetLedger budget;
    private final ApiKeyAuthenticator authenticator;
    private final Clock clock;

    public TenantAdminService(TenantRepository tenants, BudgetLedger budget, ApiKeyAuthenticator authenticator,
                              Clock clock) {
        this.tenants = tenants;
        this.budget = budget;
        this.authenticator = authenticator;
        this.clock = clock;
    }

    public Tenant create(Tenant tenant) {
        if (!tenants.insert(tenant)) {
            throw new ConflictException("tenant " + tenant.id() + " already exists");
        }
        return tenant;
    }

    public Tenant get(TenantId id) {
        return tenants.find(id).orElseThrow(() -> new NotFoundException("tenant " + id));
    }

    public List<Tenant> list() {
        return tenants.findAll();
    }

    public Tenant update(TenantId id, UnaryOperator<Tenant> change) {
        Tenant updated = change.apply(get(id));
        if (!updated.id().equals(id)) {
            throw new IllegalArgumentException("tenant id cannot change");
        }
        tenants.update(updated);
        authenticator.evictAll();
        return updated;
    }

    public Tenant deactivate(TenantId id) {
        return update(id, Tenant::deactivated);
    }

    public BudgetStatus budget(TenantId id) {
        Tenant t = get(id);
        BudgetPeriod period = BudgetPeriod.at(clock.instant());
        return new BudgetStatus(t, period, budget.usage(id, period));
    }

    public record BudgetStatus(Tenant tenant, BudgetPeriod period, BudgetLedger.Usage usage) {
    }
}
