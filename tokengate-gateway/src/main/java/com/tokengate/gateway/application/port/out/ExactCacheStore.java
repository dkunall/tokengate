package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.cache.CachedCompletion;

import java.time.Duration;
import java.util.Optional;

public interface ExactCacheStore {

    Optional<CachedCompletion> get(String key);

    void put(String key, CachedCompletion value, Duration ttl);
}
