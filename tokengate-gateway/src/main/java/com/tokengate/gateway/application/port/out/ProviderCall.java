package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.chat.ChatRequest;

import java.time.Duration;

/** The request as it leaves the gateway: already masked, with the provider's own model name. */
public record ProviderCall(ChatRequest request, String nativeModel, Duration timeout, String requestId) {
}
