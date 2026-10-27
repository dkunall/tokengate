package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent.CacheOutcome;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;

import java.util.List;

/** @param provider null when served from the cache */
public record ChatResult(
        String requestId,
        ChatCompletion completion,
        String provider,
        CacheOutcome cache,
        double similarity,
        List<Attempt> attempts,
        RateLimitDecision rateLimit,
        int piiMasked) {
}
