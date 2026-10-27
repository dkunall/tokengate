package com.tokengate.gateway.adapter.out.provider;

import com.tokengate.gateway.application.port.out.ChatProvider;
import com.tokengate.gateway.application.port.out.ChatProviderRegistry;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MapProviderRegistry implements ChatProviderRegistry {

    private final Map<ProviderId, ChatProvider> providers;

    public MapProviderRegistry(List<ChatProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(ChatProvider::id, Function.identity()));
    }

    @Override
    public Optional<ChatProvider> get(ProviderId id) {
        return Optional.ofNullable(providers.get(id));
    }
}
