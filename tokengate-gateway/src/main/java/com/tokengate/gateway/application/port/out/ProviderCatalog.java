package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.util.List;
import java.util.Optional;

/**
 * Providers come from static config (type, base url, models, prices, which env var holds the key); weight,
 * priority and the enabled flag can be changed at runtime and are persisted.
 */
public interface ProviderCatalog {

    List<ProviderDescriptor> all();

    Optional<ProviderDescriptor> find(ProviderId id);

    ProviderDescriptor updateSettings(ProviderId id, int weight, int priority, boolean enabled);
}
