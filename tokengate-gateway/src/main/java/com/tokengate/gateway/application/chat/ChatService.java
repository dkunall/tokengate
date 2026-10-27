package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.chat.FailoverExecutor.Mode;
import com.tokengate.gateway.application.chat.FailoverExecutor.Served;
import com.tokengate.gateway.application.error.NoProviderAvailableException;
import com.tokengate.gateway.application.error.UnknownModelException;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.application.port.out.ProviderCatalog;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.pii.MaskingSession;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.RouteQuery;
import com.tokengate.gateway.domain.routing.Router;
import com.tokengate.gateway.domain.tenant.Tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

/**
 * The request path for /v1/chat/completions: rate limit, mask, cache, route, reserve budget, call with
 * failover, settle, store, record. Streaming shares everything up to the upstream call and then hands over
 * to {@link StreamSession}.
 */
public final class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ProviderCatalog catalog;
    private final Router router;
    private final ProviderHealth health;
    private final FailoverExecutor failover;
    private final Admission admission;
    private final PromptGuard guard;
    private final ResponseCache cache;
    private final TokenCounter tokens;
    private final UsageAccounting accounting;
    private final GatewayMetrics metrics;
    private final GatewaySettings settings;
    private final Clock clock;

    public ChatService(ProviderCatalog catalog, Router router, ProviderHealth health, FailoverExecutor failover,
                       Admission admission, PromptGuard guard, ResponseCache cache,
                       TokenCounter tokens, UsageAccounting accounting, GatewayMetrics metrics,
                       GatewaySettings settings, Clock clock) {
        this.catalog = catalog;
        this.router = router;
        this.health = health;
        this.failover = failover;
        this.admission = admission;
        this.guard = guard;
        this.cache = cache;
        this.tokens = tokens;
        this.accounting = accounting;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    public ChatResult complete(Caller caller, String requestId, ChatRequest request) {
        long started = System.nanoTime();
        Prepared p = prepare(caller, request);
        if (p.lookup().isHit()) {
            CachedCompletion c = p.lookup().hit();
            accounting.record(new UsageAccounting.Usage(caller, requestId, request.model(), null, c.providerModel(),
                    c.usage(), Cost.ZERO, p.lookup().outcome(), false, sinceMs(started), UsageEvent.Outcome.OK, 0,
                    p.piiMasked()));
            ChatCompletion out = new ChatCompletion(p.unmask(c.content()), c.finishReason(), c.usage(),
                    c.providerModel());
            return new ChatResult(requestId, out, null, p.lookup().outcome(), p.lookup().similarity(), List.of(),
                    p.rateLimit(), p.piiMasked());
        }
        Route route = route(caller.tenant(), p);
        Served<ChatCompletion> served;
        try {
            served = failover.execute(route.plan(), p.masked().model(),
                    (provider, d, offer) -> provider.complete(call(p.masked(), d, offer.nativeModel(), requestId)),
                    settings.hedging() ? Mode.HEDGED : Mode.UNARY);
        } catch (RuntimeException e) {
            admission.release(route.reservation());
            throw e;
        }
        ChatCompletion raw = served.value();
        TokenUsage usage = actualUsage(raw.usage(), p.promptTokens(), raw.content());
        Cost cost = served.offer().price().cost(usage);
        admission.settle(route.reservation(), usage.total(), cost);
        String providerId = served.provider().id().value();
        log.debug("usage {} {}: prompt estimate {} vs reported {}, completion {}, cost {} micros", providerId,
                raw.providerModel(), p.promptTokens(), usage.promptTokens(), usage.completionTokens(), cost.micros());
        if (p.cacheable()) {
            cache.store(caller.tenantId(), p.masked(), caller.tenant().cache(), p.lookup(),
                    new CachedCompletion(raw.content(), raw.finishReason(), usage, providerId, raw.providerModel(),
                            clock.instant()));
        }
        accounting.record(new UsageAccounting.Usage(caller, requestId, request.model(), providerId,
                raw.providerModel(), usage, cost, p.lookup().outcome(), false, sinceMs(started),
                UsageEvent.Outcome.OK, served.attempts().size(), p.piiMasked()));
        ChatCompletion out = new ChatCompletion(p.unmask(raw.content()), raw.finishReason(), usage,
                raw.providerModel());
        return new ChatResult(requestId, out, providerId, p.lookup().outcome(), 0, served.attempts(), p.rateLimit(),
                p.piiMasked());
    }

    /**
     * Everything that can still turn into a plain HTTP error happens here, before the 200 and the first SSE
     * byte go out: limits, routing, and opening the upstream stream (with failover).
     */
    public StreamSession openStream(Caller caller, String requestId, ChatRequest request) {
        long started = System.nanoTime();
        Prepared p = prepare(caller, request);
        StreamSession.Context base = new StreamSession.Context(caller, requestId, request.model(), p.masked(),
                p.session(), p.promptTokens(), p.rateLimit(), p.lookup(), p.cacheable(), started);
        StreamSession.Deps deps = new StreamSession.Deps(failover, admission, cache, tokens, accounting, metrics, clock);
        if (p.lookup().isHit()) {
            return StreamSession.fromCache(base, deps);
        }
        Route route = route(caller.tenant(), p);
        Served<ChunkStream> opened;
        try {
            opened = openWithFailover(route.plan(), p.masked(), requestId);
        } catch (RuntimeException e) {
            admission.release(route.reservation());
            throw e;
        }
        return StreamSession.live(base, deps, route.plan(), route.reservation(), opened);
    }

    Served<ChunkStream> openWithFailover(List<ProviderDescriptor> plan, ChatRequest masked, String requestId) {
        return failover.execute(plan, masked.model(),
                (provider, d, offer) -> provider.openStream(call(masked, d, offer.nativeModel(), requestId)),
                Mode.STREAM_OPEN);
    }

    private Prepared prepare(Caller caller, ChatRequest request) {
        Tenant tenant = caller.tenant();
        RateLimitDecision rl = admission.admit(tenant);
        PromptGuard.Guarded g = guard.mask(tenant, request);
        ChatRequest masked = g.request();
        long promptTokens = tokens.countMessages(masked.messages());
        boolean cacheable = tenant.cache().decide(masked) == CachePolicy.Decision.CACHEABLE;
        ResponseCache.Lookup lookup = cacheable
                ? cache.lookup(tenant.id().value(), masked, tenant.cache())
                : ResponseCache.Lookup.bypass();
        return new Prepared(masked, g, promptTokens, cacheable, lookup, rl);
    }

    private Route route(Tenant tenant, Prepared p) {
        String model = p.masked().model();
        List<ProviderDescriptor> offering = catalog.all().stream().filter(d -> d.offer(model).isPresent()).toList();
        if (offering.isEmpty()) {
            throw new UnknownModelException(model);
        }
        List<ProviderDescriptor> healthy = offering.stream()
                .filter(ProviderDescriptor::enabled)
                .filter(d -> health.breaker(d.id()).wouldPermit())
                .toList();
        if (healthy.isEmpty()) {
            metrics.rejected(tenant.id().value(), "no_provider");
            throw new NoProviderAvailableException(model);
        }
        long expected = p.masked().maxTokens() != null ? p.masked().maxTokens() : settings.defaultCompletionTokens();
        RouteQuery q = new RouteQuery(model, p.promptTokens(), expected, p.masked().user());
        List<ProviderDescriptor> plan = router.plan(tenant.routing(), healthy, q);
        return new Route(plan, admission.reserve(tenant, p.promptTokens() + expected, Router.estimatedCost(plan.get(0), q)));
    }

    private static ProviderCall call(ChatRequest masked, ProviderDescriptor d, String nativeModel, String requestId) {
        return new ProviderCall(masked, nativeModel, d.timeout(), requestId);
    }

    TokenUsage actualUsage(TokenUsage reported, long promptEstimate, String content) {
        if (reported != null && reported.total() > 0) {
            return reported;
        }
        return new TokenUsage(promptEstimate, content == null ? 0 : tokens.countText(content));
    }

    static long sinceMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private record Prepared(ChatRequest masked, PromptGuard.Guarded guarded, long promptTokens, boolean cacheable,
                            ResponseCache.Lookup lookup, RateLimitDecision rateLimit) {

        MaskingSession session() {
            return guarded.session();
        }

        int piiMasked() {
            return guarded.session().total();
        }

        String unmask(String text) {
            return guarded.session().unmask(text);
        }
    }

    /** @param reservation null if the ledger was unreachable and the request was let through */
    private record Route(List<ProviderDescriptor> plan, BudgetReservation reservation) {
    }
}
