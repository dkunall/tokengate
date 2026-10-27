package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.cache.LruTtlCache;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.pii.PiiMasker;
import com.tokengate.gateway.domain.pii.PiiMode;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ModelOffer;
import com.tokengate.gateway.domain.routing.Price;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderId;
import com.tokengate.gateway.domain.routing.ProviderType;
import com.tokengate.gateway.domain.routing.RetryPolicy;
import com.tokengate.gateway.domain.routing.Router;
import com.tokengate.gateway.domain.routing.RoutingPolicy;
import com.tokengate.gateway.domain.tenant.PiiSettings;
import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

/** A ChatService over in-memory fakes, with two providers: alpha (cheap) and beta (pricier). */
final class Harness {

    static final String MODEL = "tg-small";

    final Fakes.ScriptedProvider alpha = new Fakes.ScriptedProvider("alpha");
    final Fakes.ScriptedProvider beta = new Fakes.ScriptedProvider("beta");
    final Fakes.Catalog catalog = new Fakes.Catalog()
            .add(descriptor("alpha", new Price(100, 200)))
            .add(descriptor("beta", new Price(500, 1000)));
    final Fakes.Registry registry = new Fakes.Registry().add(alpha).add(beta);
    final Fakes.Limiter limiter = new Fakes.Limiter();
    final Fakes.Ledger ledger = new Fakes.Ledger();
    final Fakes.Exact exact = new Fakes.Exact();
    final Fakes.Semantic semantic = new Fakes.Semantic();
    final Fakes.Recorder recorder = new Fakes.Recorder();
    final Fakes.Metrics metrics = new Fakes.Metrics();
    final List<Duration> sleeps = new java.util.concurrent.CopyOnWriteArrayList<>();
    final LruTtlCache<String, com.tokengate.gateway.domain.cache.CachedCompletion> l1 =
            LruTtlCache.create(100, Duration.ofMinutes(1));
    final Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), java.time.ZoneOffset.UTC);
    final ExecutorService hedgePool = Executors.newVirtualThreadPerTaskExecutor();

    final ProviderHealth health = new ProviderHealth(CircuitBreaker.Config.defaults(), clock, 0.2, metrics);
    final ResponseCache cache = new ResponseCache(l1, exact, semantic, new Fakes.WordEmbeddings(), metrics);
    final Admission admission = new Admission(limiter, ledger, metrics, clock);

    ChatService service(Duration hedgeAfter) {
        FailoverExecutor failover = failover(hedgeAfter);
        GatewaySettings defaults = GatewaySettings.defaults();
        GatewaySettings settings = new GatewaySettings(defaults.defaultCompletionTokens(), defaults.retry(),
                defaults.breaker(), defaults.latencyEwmaAlpha(), hedgeAfter, defaults.l1Capacity(), defaults.l1Ttl());
        return new ChatService(catalog, new Router(health::latencyMillis), health, failover, admission,
                new PromptGuard(new PiiMasker("unit-test-pii-key-not-secret".getBytes(StandardCharsets.UTF_8)), metrics), cache,
                new Fakes.WordTokens(), new UsageAccounting(recorder, metrics, clock), metrics, settings, clock);
    }

    ChatService service() {
        return service(null);
    }

    FailoverExecutor failover(Duration hedgeAfter) {
        return new FailoverExecutor(registry, health, RetryPolicy.defaults(), metrics, sleeps::add,
                RandomGenerator.of("L64X128MixRandom"), hedgePool, hedgeAfter);
    }

    static Tenant tenant(PiiMode pii, long dailyTokens) {
        return new Tenant(TenantId.of("acme"), "Acme", new RateLimitRule(60, 10),
                new BudgetLimits(dailyTokens, Cost.ofUsd("100")), RoutingPolicy.COST, CachePolicy.defaults(),
                new PiiSettings(pii, List.of()), true, Instant.parse("2026-01-01T00:00:00Z"));
    }

    static Caller caller() {
        return new Caller(tenant(PiiMode.TOKENIZE, 1_000_000), "key-1");
    }

    static ChatRequest ask(String text, Double temperature, boolean stream) {
        return new ChatRequest(MODEL, List.of(ChatMessage.user(text)), temperature, null, 50, null, stream, null);
    }

    private static ProviderDescriptor descriptor(String id, Price price) {
        return new ProviderDescriptor(ProviderId.of(id), ProviderType.MOCK,
                Map.of(MODEL, new ModelOffer(id + "-native", price)), 1, 1, true, Duration.ofSeconds(5));
    }
}
