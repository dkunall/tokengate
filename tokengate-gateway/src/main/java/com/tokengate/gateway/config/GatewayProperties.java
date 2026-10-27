package com.tokengate.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("tokengate")
public record GatewayProperties(
        List<Provider> providers,
        @DefaultValue Routing routing,
        @DefaultValue Cache cache,
        @DefaultValue Embedding embedding,
        @DefaultValue Security security,
        @DefaultValue Usage usage) {

    public GatewayProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * {@code apiKey} is bound from an environment variable in application.yml. A real provider without a key is
     * skipped at startup rather than failing every call.
     */
    public record Provider(
            String id,
            String type,
            URI baseUrl,
            String apiKey,
            @DefaultValue("100") int weight,
            @DefaultValue("10") int priority,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("false") boolean embeddings,
            Map<String, Model> models) {
    }

    /** Prices in USD per 1,000 tokens, as the providers publish them. */
    public record Model(String nativeModel, BigDecimal inputPer1k, BigDecimal outputPer1k) {
    }

    public record Routing(
            @DefaultValue("256") long defaultCompletionTokens,
            @DefaultValue("2") int maxAttemptsPerProvider,
            @DefaultValue("100ms") Duration backoffBase,
            @DefaultValue("2s") Duration backoffCap,
            @DefaultValue("20") int breakerWindow,
            @DefaultValue("5") int breakerMinimumCalls,
            @DefaultValue("0.5") double breakerFailureRate,
            @DefaultValue("15s") Duration breakerOpenFor,
            @DefaultValue("2") int breakerHalfOpenTrials,
            @DefaultValue("0.2") double latencyEwmaAlpha,
            Duration hedgeAfter,
            @DefaultValue("20s") Duration streamIdleTimeout,
            @DefaultValue("2s") Duration connectTimeout) {
    }

    public record Cache(
            @DefaultValue("10000") int l1Capacity,
            @DefaultValue("30s") Duration l1Ttl,
            @DefaultValue("10m") Duration semanticPurgeInterval) {
    }

    public record Embedding(
            Path modelDir,
            @DefaultValue("256") int maxSequenceLength,
            @DefaultValue("2") int threads,
            @DefaultValue("384") int hashingDimensions) {
    }

    public record Security(
            String piiKey,
            String apiKeyPepper,
            @DefaultValue("60s") Duration apiKeyCacheTtl,
            @DefaultValue Jwt jwt) {
    }

    /**
     * Admin endpoints accept HS256 tokens signed with {@code hmacSecret}, or RS256 from {@code issuerUri} when
     * that is set (a real IdP). There is no token-minting endpoint; scripts/admin-token.sh signs one locally.
     */
    public record Jwt(
            String issuerUri,
            String hmacSecret,
            @DefaultValue("tokengate-dev") String issuer) {
    }

    public record Usage(
            @DefaultValue("tokengate.usage.v1") String topic,
            @DefaultValue("1s") Duration outboxInterval,
            @DefaultValue("200") int outboxBatch,
            @DefaultValue("5s") Duration kafkaAckTimeout) {
    }
}
