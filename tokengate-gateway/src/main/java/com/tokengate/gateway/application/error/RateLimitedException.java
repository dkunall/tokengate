package com.tokengate.gateway.application.error;

import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;

public final class RateLimitedException extends GatewayException {

    private final RateLimitDecision decision;

    public RateLimitedException(RateLimitDecision decision) {
        super("rate limit exceeded, retry in " + decision.retryAfterSeconds() + "s");
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
