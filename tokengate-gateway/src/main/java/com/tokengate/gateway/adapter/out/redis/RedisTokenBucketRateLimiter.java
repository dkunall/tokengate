package com.tokengate.gateway.adapter.out.redis;

import com.tokengate.gateway.application.port.out.RateLimiter;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = loaded("redis/token_bucket.lua");

    private final StringRedisTemplate redis;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitDecision tryAcquire(TenantId tenant, RateLimitRule rule, long permits) {
        List<?> r = redis.execute(SCRIPT, List.of(RedisKeys.bucket(tenant.value())),
                Double.toString(rule.refillPerSecond()), Long.toString(rule.burst()), Long.toString(permits));
        if (r == null || r.size() < 4) {
            throw new IllegalStateException("unexpected reply from token bucket script: " + r);
        }
        return new RateLimitDecision(
                ((Number) r.get(0)).longValue() == 1,
                rule.burst(),
                ((Number) r.get(1)).longValue(),
                Duration.ofMillis(((Number) r.get(2)).longValue()),
                Duration.ofMillis(((Number) r.get(3)).longValue()));
    }

    @SuppressWarnings("rawtypes")
    static RedisScript<List> loaded(String path) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(List.class);
        return s;
    }
}
