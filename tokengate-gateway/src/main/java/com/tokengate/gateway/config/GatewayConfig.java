package com.tokengate.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.adapter.out.embedding.HashingEmbeddingProvider;
import com.tokengate.gateway.adapter.out.embedding.OnnxMiniLmEmbeddingProvider;
import com.tokengate.gateway.adapter.out.kafka.KafkaUsageEventPublisher;
import com.tokengate.gateway.adapter.out.persistence.ConfiguredProviderCatalog;
import com.tokengate.gateway.adapter.out.provider.MapProviderRegistry;
import com.tokengate.gateway.application.admin.ApiKeyAdminService;
import com.tokengate.gateway.application.admin.ProviderAdminService;
import com.tokengate.gateway.application.admin.TenantAdminService;
import com.tokengate.gateway.application.auth.ApiKeyAuthenticator;
import com.tokengate.gateway.application.chat.Admission;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.application.chat.ChatService;
import com.tokengate.gateway.application.chat.FailoverExecutor;
import com.tokengate.gateway.application.chat.GatewaySettings;
import com.tokengate.gateway.application.chat.PromptGuard;
import com.tokengate.gateway.application.chat.ProviderHealth;
import com.tokengate.gateway.application.chat.ResponseCache;
import com.tokengate.gateway.application.chat.Sleeper;
import com.tokengate.gateway.application.chat.UsageAccounting;
import com.tokengate.gateway.application.embedding.EmbeddingsService;
import com.tokengate.gateway.application.outbox.OutboxRelay;
import com.tokengate.gateway.application.port.out.ApiKeyRepository;
import com.tokengate.gateway.application.port.out.BudgetLedger;
import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.application.port.out.ExactCacheStore;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.OutboxStore;
import com.tokengate.gateway.application.port.out.ChatProviderRegistry;
import com.tokengate.gateway.application.port.out.RateLimiter;
import com.tokengate.gateway.application.port.out.SemanticCacheStore;
import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.application.port.out.UsageRecorder;
import com.tokengate.gateway.domain.cache.LruTtlCache;
import com.tokengate.gateway.domain.pii.PiiMasker;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.RetryPolicy;
import com.tokengate.gateway.domain.routing.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Random;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);
    static final String DEV_MARKER = "dev-only";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    HttpClient providerHttpClient(GatewayProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.routing().connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    ProviderWiring.Wired providerWiring(GatewayProperties props, HttpClient http, ObjectMapper json) {
        ProviderWiring.Wired wired = ProviderWiring.wire(props, http, json);
        if (wired.adapters().isEmpty()) {
            throw new IllegalStateException("no providers configured; set at least one under tokengate.providers");
        }
        return wired;
    }

    @Bean
    ConfiguredProviderCatalog providerCatalog(ProviderWiring.Wired wired, JdbcClient jdbc) {
        return new ConfiguredProviderCatalog(wired.descriptors(), jdbc);
    }

    @Bean
    ChatProviderRegistry providerRegistry(ProviderWiring.Wired wired) {
        return new MapProviderRegistry(wired.adapters());
    }

    @Bean
    GatewaySettings gatewaySettings(GatewayProperties props) {
        GatewayProperties.Routing r = props.routing();
        return new GatewaySettings(r.defaultCompletionTokens(),
                new RetryPolicy(r.maxAttemptsPerProvider(), r.backoffBase(), r.backoffCap()),
                new CircuitBreaker.Config(r.breakerWindow(), r.breakerMinimumCalls(), r.breakerFailureRate(),
                        r.breakerOpenFor(), r.breakerHalfOpenTrials()),
                r.latencyEwmaAlpha(), r.hedgeAfter(), props.cache().l1Capacity(), props.cache().l1Ttl());
    }

    @Bean
    ProviderHealth providerHealth(GatewaySettings s, Clock clock, GatewayMetrics metrics) {
        return new ProviderHealth(s.breaker(), clock, s.latencyEwmaAlpha(), metrics);
    }

    @Bean
    Router router(ProviderHealth health) {
        return new Router(health::latencyMillis);
    }

    @Bean(destroyMethod = "close")
    ExecutorService hedgePool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    FailoverExecutor failoverExecutor(ChatProviderRegistry registry, ProviderHealth health, GatewaySettings s,
                                      GatewayMetrics metrics, ExecutorService hedgePool) {
        return new FailoverExecutor(registry, health, s.retry(), metrics, Sleeper.REAL, new Random(),
                hedgePool, s.hedgeAfter());
    }

    @Bean
    EmbeddingProvider embeddingProvider(GatewayProperties props) {
        GatewayProperties.Embedding e = props.embedding();
        if (e.modelDir() != null && Files.isRegularFile(e.modelDir().resolve("model.onnx"))) {
            try {
                EmbeddingProvider onnx = new OnnxMiniLmEmbeddingProvider(e.modelDir(), e.maxSequenceLength(),
                        e.threads());
                log.info("semantic cache embeddings: {} ({} dims) from {}", onnx.modelName(), onnx.dimensions(),
                        e.modelDir());
                return onnx;
            } catch (Exception ex) {
                log.warn("could not load ONNX model from {}: {}", e.modelDir(), ex.toString());
            }
        }
        HashingEmbeddingProvider fallback = new HashingEmbeddingProvider(e.hashingDimensions());
        log.warn("semantic cache embeddings: {} fallback (lexical, not semantic); set tokengate.embedding.model-dir"
                + " to an all-MiniLM-L6-v2 ONNX export for real semantic matching", fallback.modelName());
        return fallback;
    }

    @Bean
    ResponseCache responseCache(GatewaySettings s, ExactCacheStore exact, SemanticCacheStore semantic,
                                EmbeddingProvider embeddings, GatewayMetrics metrics) {
        LruTtlCache<String, CachedCompletion> l1 = new LruTtlCache<>(s.l1Capacity(), s.l1Ttl(), System::nanoTime);
        return new ResponseCache(l1, exact, semantic, embeddings, metrics);
    }

    @Bean
    PromptGuard promptGuard(GatewayProperties props, GatewayMetrics metrics) {
        return new PromptGuard(new PiiMasker(secret("tokengate.security.pii-key", props.security().piiKey())),
                metrics);
    }

    @Bean
    Admission admission(RateLimiter limiter, BudgetLedger budget, GatewayMetrics metrics, Clock clock) {
        return new Admission(limiter, budget, metrics, clock);
    }

    @Bean
    UsageAccounting usageAccounting(UsageRecorder recorder, GatewayMetrics metrics, Clock clock) {
        return new UsageAccounting(recorder, metrics, clock);
    }

    @Bean
    ChatService chatService(ConfiguredProviderCatalog catalog, Router router, ProviderHealth health,
                            FailoverExecutor failover, Admission admission, PromptGuard guard, ResponseCache cache,
                            TokenCounter tokens, UsageAccounting accounting, GatewayMetrics metrics,
                            GatewaySettings settings, Clock clock) {
        return new ChatService(catalog, router, health, failover, admission, guard, cache, tokens, accounting,
                metrics, settings, clock);
    }

    @Bean
    EmbeddingsService embeddingsService(EmbeddingProvider local, ConfiguredProviderCatalog catalog,
                                        ChatProviderRegistry registry, ProviderHealth health, Router router,
                                        FailoverExecutor failover, Admission admission, PromptGuard guard,
                                        TokenCounter tokens, UsageAccounting accounting) {
        return new EmbeddingsService(local, catalog, registry, health, router, failover, admission, guard, tokens,
                accounting);
    }

    @Bean
    ApiKeyAuthenticator apiKeyAuthenticator(ApiKeyRepository keys, TenantRepository tenants,
                                            GatewayProperties props) {
        return new ApiKeyAuthenticator(keys, tenants, pepper(props), props.security().apiKeyCacheTtl());
    }

    @Bean
    TenantAdminService tenantAdminService(TenantRepository tenants, BudgetLedger budget,
                                          ApiKeyAuthenticator authenticator, Clock clock) {
        return new TenantAdminService(tenants, budget, authenticator, clock);
    }

    @Bean
    ApiKeyAdminService apiKeyAdminService(ApiKeyRepository keys, TenantRepository tenants,
                                          ApiKeyAuthenticator authenticator, GatewayProperties props, Clock clock) {
        return new ApiKeyAdminService(keys, tenants, authenticator, pepper(props), clock);
    }

    @Bean
    ProviderAdminService providerAdminService(ConfiguredProviderCatalog catalog, ProviderHealth health) {
        return new ProviderAdminService(catalog, health);
    }

    @Bean
    KafkaUsageEventPublisher usageEventPublisher(KafkaTemplate<String, String> kafka, GatewayProperties props) {
        return new KafkaUsageEventPublisher(kafka, props.usage().topic(), props.usage().kafkaAckTimeout());
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore outbox, KafkaUsageEventPublisher publisher, GatewayProperties props) {
        return new OutboxRelay(outbox, publisher, props.usage().outboxBatch());
    }

    private static byte[] pepper(GatewayProperties props) {
        return secret("tokengate.security.api-key-pepper", props.security().apiKeyPepper());
    }

    static byte[] secret(String name, String value) {
        if (value == null || value.length() < 32) {
            throw new IllegalStateException(name + " must be set (from an environment variable) and be at least"
                    + " 32 characters");
        }
        if (value.startsWith(DEV_MARKER)) {
            log.warn("{} is using the built-in development value; set it from the environment before real use",
                    name);
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
