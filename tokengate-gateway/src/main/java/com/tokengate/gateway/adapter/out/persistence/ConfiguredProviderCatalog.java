package com.tokengate.gateway.adapter.out.persistence;

import com.tokengate.gateway.application.error.NotFoundException;
import com.tokengate.gateway.application.port.out.ProviderCatalog;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Providers, their models and prices come from configuration. Weight, priority and enabled can be changed at
 * runtime through the admin API; those overrides live in provider_settings and are re-read periodically so every
 * gateway instance converges on them.
 */
public class ConfiguredProviderCatalog implements ProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredProviderCatalog.class);

    private final Map<ProviderId, ProviderDescriptor> configured;
    private final Map<ProviderId, ProviderDescriptor> current = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;

    public ConfiguredProviderCatalog(List<ProviderDescriptor> configured, JdbcClient jdbc) {
        Map<ProviderId, ProviderDescriptor> byId = new LinkedHashMap<>();
        configured.forEach(d -> byId.put(d.id(), d));
        this.configured = Map.copyOf(byId);
        this.current.putAll(byId);
        this.jdbc = jdbc;
    }

    @Override
    public List<ProviderDescriptor> all() {
        return current.values().stream().sorted(Comparator.comparing(ProviderDescriptor::id)).toList();
    }

    @Override
    public Optional<ProviderDescriptor> find(ProviderId id) {
        return Optional.ofNullable(current.get(id));
    }

    @Override
    public ProviderDescriptor updateSettings(ProviderId id, int weight, int priority, boolean enabled) {
        ProviderDescriptor base = configured.get(id);
        if (base == null) {
            throw new NotFoundException("provider " + id);
        }
        jdbc.sql("""
                insert into provider_settings (provider_id, weight, priority, enabled, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (provider_id) do update
                set weight = excluded.weight, priority = excluded.priority, enabled = excluded.enabled,
                    updated_at = now()""")
                .params(id.value(), weight, priority, enabled)
                .update();
        ProviderDescriptor updated = base.withSettings(weight, priority, enabled);
        current.put(id, updated);
        return updated;
    }

    public void refresh() {
        try {
            jdbc.sql("select provider_id, weight, priority, enabled from provider_settings")
                    .query((rs, n) -> {
                        ProviderId id = ProviderId.of(rs.getString("provider_id"));
                        ProviderDescriptor base = configured.get(id);
                        if (base != null) {
                            current.put(id, base.withSettings(rs.getInt("weight"), rs.getInt("priority"),
                                    rs.getBoolean("enabled")));
                        }
                        return id;
                    })
                    .list();
        } catch (RuntimeException e) {
            log.warn("could not refresh provider settings, keeping the last known ones: {}", e.toString());
        }
    }
}
