package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.cache.CachedCompletion;

import java.time.Duration;
import java.util.Optional;

/**
 * Entries are partitioned by tenant and by context key (model, sampling params, every message except the
 * last user turn), so a semantic match only ever compares the final question.
 */
public interface SemanticCacheStore {

    Optional<Hit> nearest(String tenantId, String contextKey, float[] embedding, double minSimilarity);

    void put(String tenantId, String contextKey, String maskedPrompt, float[] embedding, CachedCompletion value,
             Duration ttl);

    int purgeExpired();

    record Hit(CachedCompletion completion, double similarity, String matchedPrompt) {
    }
}
