package com.tokengate.gateway.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.application.port.out.ExactCacheStore;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisExactCache implements ExactCacheStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisExactCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Optional<CachedCompletion> get(String key) {
        String raw = redis.opsForValue().get(RedisKeys.exact(key));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(raw, CachedCompletion.class));
        } catch (JsonProcessingException e) {
            redis.delete(RedisKeys.exact(key));
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, CachedCompletion value, Duration ttl) {
        try {
            redis.opsForValue().set(RedisKeys.exact(key), json.writeValueAsString(value), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
