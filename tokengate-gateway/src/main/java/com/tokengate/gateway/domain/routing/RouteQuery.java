package com.tokengate.gateway.domain.routing;

/** What a strategy needs to know about one request to rank providers for it. */
public record RouteQuery(String model, long estimatedPromptTokens, long expectedCompletionTokens, String stickyKey) {
}
