package com.tokengate.gateway.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.application.port.out.SemanticCacheStore;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Nearest neighbour by cosine distance ({@code <=>}) inside one tenant and one conversation context.
 * Embeddings are L2-normalised before they get here, so similarity is {@code 1 - distance}.
 */
@Repository
public class PgVectorSemanticCache implements SemanticCacheStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public PgVectorSemanticCache(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<Hit> nearest(String tenantId, String contextKey, float[] embedding, double minSimilarity) {
        String vector = literal(embedding);
        return jdbc.sql("""
                select prompt, completion::text as completion, 1 - (embedding <=> ?::vector) as similarity
                from semantic_cache
                where tenant_id = ? and context_key = ? and expires_at > ?
                order by embedding <=> ?::vector
                limit 1""")
                .params(vector, tenantId, contextKey, Timestamp.from(clock.instant()), vector)
                .query((rs, n) -> new Hit(read(rs.getString("completion")), rs.getDouble("similarity"),
                        rs.getString("prompt")))
                .optional()
                .filter(h -> h.similarity() >= minSimilarity);
    }

    @Override
    public void put(String tenantId, String contextKey, String maskedPrompt, float[] embedding,
                    CachedCompletion value, Duration ttl) {
        jdbc.sql("""
                insert into semantic_cache (tenant_id, context_key, prompt, embedding, completion, expires_at)
                values (?, ?, ?, ?::vector, ?::jsonb, ?)""")
                .params(tenantId, contextKey, maskedPrompt, literal(embedding), write(value),
                        Timestamp.from(clock.instant().plus(ttl)))
                .update();
    }

    @Override
    public int purgeExpired() {
        return jdbc.sql("delete from semantic_cache where expires_at <= ?")
                .param(Timestamp.from(clock.instant())).update();
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private String write(CachedCompletion c) {
        try {
            return json.writeValueAsString(c);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private CachedCompletion read(String s) {
        try {
            return json.readValue(s, CachedCompletion.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
