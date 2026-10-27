package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent.CacheOutcome;
import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.application.port.out.ExactCacheStore;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.SemanticCacheStore;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.domain.cache.LruTtlCache;
import com.tokengate.gateway.domain.cache.PromptFingerprint;
import com.tokengate.gateway.domain.chat.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Three tiers, cheapest first: in-process LRU, Redis exact match, pgvector nearest neighbour. Every tier
 * fails open: a cache outage costs money, not availability.
 */
public final class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    private final LruTtlCache<String, CachedCompletion> l1;
    private final ExactCacheStore exact;
    private final SemanticCacheStore semantic;
    private final EmbeddingProvider embeddings;
    private final GatewayMetrics metrics;

    public ResponseCache(LruTtlCache<String, CachedCompletion> l1, ExactCacheStore exact, SemanticCacheStore semantic,
                         EmbeddingProvider embeddings, GatewayMetrics metrics) {
        this.l1 = l1;
        this.exact = exact;
        this.semantic = semantic;
        this.embeddings = embeddings;
        this.metrics = metrics;
    }

    /** The request must already be masked; keys and stored prompts never contain raw PII. */
    public Lookup lookup(String tenantId, ChatRequest masked, CachePolicy policy) {
        String key = PromptFingerprint.exactKey(tenantId, masked);
        Optional<CachedCompletion> local = l1.get(key);
        metrics.cacheLookup("l1", local.isPresent());
        if (local.isPresent()) {
            return new Lookup(key, local.get(), CacheOutcome.L1_HIT, 1.0, null);
        }
        Optional<CachedCompletion> remote = safely("exact get", () -> exact.get(key)).flatMap(o -> o);
        metrics.cacheLookup("exact", remote.isPresent());
        if (remote.isPresent()) {
            l1.put(key, remote.get());
            return new Lookup(key, remote.get(), CacheOutcome.EXACT_HIT, 1.0, null);
        }
        if (!policy.semanticEnabled()) {
            return new Lookup(key, null, CacheOutcome.MISS, 0, null);
        }
        float[] vector = safely("embed", () -> embeddings.embed(PromptFingerprint.semanticText(masked))).orElse(null);
        if (vector == null) {
            return new Lookup(key, null, CacheOutcome.MISS, 0, null);
        }
        String context = PromptFingerprint.contextKey(tenantId, masked);
        Optional<SemanticCacheStore.Hit> near = safely("semantic get",
                () -> semantic.nearest(tenantId, context, vector, policy.semanticThreshold())).flatMap(o -> o);
        metrics.cacheLookup("semantic", near.isPresent());
        return near.map(h -> new Lookup(key, h.completion(), CacheOutcome.SEMANTIC_HIT, h.similarity(), vector))
                .orElseGet(() -> new Lookup(key, null, CacheOutcome.MISS, 0, vector));
    }

    public void store(String tenantId, ChatRequest masked, CachePolicy policy, Lookup miss, CachedCompletion value) {
        l1.put(miss.key(), value);
        safely("exact put", () -> {
            exact.put(miss.key(), value, policy.ttl());
            return true;
        });
        if (policy.semanticEnabled() && miss.embedding() != null) {
            String context = PromptFingerprint.contextKey(tenantId, masked);
            safely("semantic put", () -> {
                semantic.put(tenantId, context, PromptFingerprint.semanticText(masked), miss.embedding(), value,
                        policy.ttl());
                return true;
            });
        }
    }

    private static <T> Optional<T> safely(String what, Supplier<T> op) {
        try {
            return Optional.ofNullable(op.get());
        } catch (RuntimeException e) {
            log.warn("cache {} failed, treating as a miss: {}", what, e.toString());
            return Optional.empty();
        }
    }

    /** {@code embedding} is kept from the lookup so a miss is not embedded twice when it gets stored. */
    public record Lookup(String key, CachedCompletion hit, CacheOutcome outcome, double similarity, float[] embedding) {

        public static Lookup bypass() {
            return new Lookup(null, null, CacheOutcome.BYPASS, 0, null);
        }

        public boolean isHit() {
            return hit != null;
        }
    }
}
