package com.tokengate.gateway.domain.routing;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ProviderDescriptor(
        ProviderId id,
        ProviderType type,
        Map<String, ModelOffer> models,
        int weight,
        int priority,
        boolean enabled,
        Duration timeout) {

    public ProviderDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timeout, "timeout");
        if (weight < 0) {
            throw new IllegalArgumentException("weight cannot be negative");
        }
        models = Map.copyOf(models);
    }

    /**
     * Requests can name a logical model ("tg-small") that several providers offer, or a provider's native
     * model id ("gpt-4o-mini") to pin it to whoever serves that.
     */
    public Optional<ModelOffer> offer(String requestedModel) {
        ModelOffer direct = models.get(requestedModel);
        if (direct != null) {
            return Optional.of(direct);
        }
        return models.values().stream().filter(o -> o.nativeModel().equals(requestedModel)).findFirst();
    }

    public ProviderDescriptor withSettings(int newWeight, int newPriority, boolean nowEnabled) {
        return new ProviderDescriptor(id, type, models, newWeight, newPriority, nowEnabled, timeout);
    }
}
