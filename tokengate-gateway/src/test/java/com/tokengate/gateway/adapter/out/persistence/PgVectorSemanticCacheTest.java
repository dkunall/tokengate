package com.tokengate.gateway.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tokengate.gateway.application.port.out.SemanticCacheStore;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.routing.MutableClock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PgVectorSemanticCacheTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    private static final String CTX = "c".repeat(64);
    private static JdbcClient jdbc;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private PgVectorSemanticCache cache;

    @BeforeAll
    static void start() {
        PG.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate semantic_cache").update();
        cache = new PgVectorSemanticCache(jdbc, json, clock);
    }

    @Test
    void returnsTheNearestEntryWithItsCosineSimilarity() {
        cache.put("acme", CTX, "far", axis(1), completion("far"), Duration.ofHours(1));
        cache.put("acme", CTX, "near", mix(0, 1, 0.2f), completion("near"), Duration.ofHours(1));

        Optional<SemanticCacheStore.Hit> hit = cache.nearest("acme", CTX, axis(0), 0.9);

        assertThat(hit).hasValueSatisfying(h -> {
            assertThat(h.matchedPrompt()).isEqualTo("near");
            assertThat(h.completion().content()).isEqualTo("near");
            assertThat(h.completion().usage().completionTokens()).isEqualTo(3);
            // cos between (1,0) and (1,0.2) normalised
            assertThat(h.similarity()).isCloseTo(1 / Math.sqrt(1.04), within(1e-4));
        });
    }

    @Test
    void belowThresholdIsAMiss() {
        cache.put("acme", CTX, "p", mix(0, 1, 1f), completion("x"), Duration.ofHours(1));
        assertThat(cache.nearest("acme", CTX, axis(0), 0.9)).isEmpty();
        assertThat(cache.nearest("acme", CTX, axis(0), 0.7)).isPresent();
    }

    @Test
    void neverCrossesTenantOrContext() {
        cache.put("acme", CTX, "p", axis(0), completion("acme's"), Duration.ofHours(1));
        assertThat(cache.nearest("globex", CTX, axis(0), 0.5)).isEmpty();
        assertThat(cache.nearest("acme", "d".repeat(64), axis(0), 0.5)).isEmpty();
    }

    @Test
    void expiredEntriesAreInvisibleAndThenPurged() {
        cache.put("acme", CTX, "p", axis(0), completion("x"), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));
        assertThat(cache.nearest("acme", CTX, axis(0), 0.5)).isEmpty();
        assertThat(cache.purgeExpired()).isEqualTo(1);
    }

    private static CachedCompletion completion(String content) {
        return new CachedCompletion(content, "stop", new TokenUsage(7, 3), "mock-alpha", "mock-alpha-small",
                Instant.parse("2026-10-27T09:00:00Z"));
    }

    private static float[] axis(int i) {
        float[] v = new float[384];
        v[i] = 1;
        return v;
    }

    private static float[] mix(int a, int b, float weightB) {
        float[] v = axis(a);
        v[b] = weightB;
        return v;
    }
}
