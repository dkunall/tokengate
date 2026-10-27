package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.tenant.TenantId;

public interface RateLimiter {

    RateLimitDecision tryAcquire(TenantId tenant, RateLimitRule rule, long permits);
}
