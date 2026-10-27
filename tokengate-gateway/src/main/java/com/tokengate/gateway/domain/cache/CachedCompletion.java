package com.tokengate.gateway.domain.cache;

import com.tokengate.gateway.domain.chat.TokenUsage;

import java.time.Instant;

/** What gets stored. Content is stored after PII masking, never with the caller's raw values. */
public record CachedCompletion(String content, String finishReason, TokenUsage usage, String provider,
                               String providerModel, Instant cachedAt) {
}
