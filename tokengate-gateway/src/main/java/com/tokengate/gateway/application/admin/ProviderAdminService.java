package com.tokengate.gateway.application.admin;

import com.tokengate.gateway.application.chat.ProviderHealth;
import com.tokengate.gateway.application.error.NotFoundException;
import com.tokengate.gateway.application.port.out.ProviderCatalog;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.util.List;

public final class ProviderAdminService {

    private final ProviderCatalog catalog;
    private final ProviderHealth health;

    public ProviderAdminService(ProviderCatalog catalog, ProviderHealth health) {
        this.catalog = catalog;
        this.health = health;
    }

    public List<ProviderStatus> list() {
        return catalog.all().stream().map(d -> new ProviderStatus(d, health.snapshot(d.id()))).toList();
    }

    public ProviderStatus update(ProviderId id, Integer weight, Integer priority, Boolean enabled) {
        ProviderDescriptor current = catalog.find(id).orElseThrow(() -> new NotFoundException("provider " + id));
        if (weight != null && weight < 0) {
            throw new IllegalArgumentException("weight cannot be negative");
        }
        ProviderDescriptor updated = catalog.updateSettings(id,
                weight != null ? weight : current.weight(),
                priority != null ? priority : current.priority(),
                enabled != null ? enabled : current.enabled());
        return new ProviderStatus(updated, health.snapshot(id));
    }

    public record ProviderStatus(ProviderDescriptor descriptor, ProviderHealth.Snapshot health) {
    }
}
