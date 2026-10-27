package com.tokengate.gateway.domain.cache;

import com.tokengate.gateway.domain.chat.ChatRequest;

import java.time.Duration;

/** Per-tenant cache settings. The defaults are conservative: only temperature=0, non-streamed calls are cached. */
public record CachePolicy(
        boolean enabled,
        boolean semanticEnabled,
        double semanticThreshold,
        Duration ttl,
        boolean cacheStreamed,
        boolean cacheNonDeterministic) {

    public enum Decision { CACHEABLE, BYPASS_DISABLED, BYPASS_STREAM, BYPASS_NON_DETERMINISTIC }

    public CachePolicy {
        if (semanticThreshold <= 0 || semanticThreshold > 1) {
            throw new IllegalArgumentException("semanticThreshold must be in (0, 1]");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    public static CachePolicy defaults() {
        return new CachePolicy(true, true, 0.90, Duration.ofHours(1), false, false);
    }

    public Decision decide(ChatRequest request) {
        if (!enabled) {
            return Decision.BYPASS_DISABLED;
        }
        if (request.stream() && !cacheStreamed) {
            return Decision.BYPASS_STREAM;
        }
        if (!request.deterministic() && !cacheNonDeterministic) {
            return Decision.BYPASS_NON_DETERMINISTIC;
        }
        return Decision.CACHEABLE;
    }
}
