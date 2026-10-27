package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.routing.ProviderId;

import java.util.Optional;

public interface ChatProviderRegistry {

    Optional<ChatProvider> get(ProviderId id);
}
