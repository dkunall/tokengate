package com.tokengate.gateway.application.embedding;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.chat.Admission;
import com.tokengate.gateway.application.chat.Caller;
import com.tokengate.gateway.application.chat.FailoverExecutor;
import com.tokengate.gateway.application.chat.PromptGuard;
import com.tokengate.gateway.application.chat.ProviderHealth;
import com.tokengate.gateway.application.chat.UsageAccounting;
import com.tokengate.gateway.application.error.NoProviderAvailableException;
import com.tokengate.gateway.application.error.UnknownModelException;
import com.tokengate.gateway.application.port.out.ChatProviderRegistry;
import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.application.port.out.ProviderCatalog;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.chat.EmbeddingRequest;
import com.tokengate.gateway.domain.chat.EmbeddingResult;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.pii.MaskingSession;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.RouteQuery;
import com.tokengate.gateway.domain.routing.Router;
import com.tokengate.gateway.domain.tenant.Tenant;

import java.util.List;

/**
 * /v1/embeddings. The local model (the same one the semantic cache uses) is served in-process and free;
 * any other model name is routed to a provider like a chat call. Input is masked first, so an embedding never
 * encodes a raw email or card number.
 */
public final class EmbeddingsService {

    private final EmbeddingProvider local;
    private final ProviderCatalog catalog;
    private final ChatProviderRegistry registry;
    private final ProviderHealth health;
    private final Router router;
    private final FailoverExecutor failover;
    private final Admission admission;
    private final PromptGuard guard;
    private final TokenCounter tokens;
    private final UsageAccounting accounting;

    public EmbeddingsService(EmbeddingProvider local, ProviderCatalog catalog, ChatProviderRegistry registry,
                             ProviderHealth health, Router router, FailoverExecutor failover, Admission admission,
                             PromptGuard guard, TokenCounter tokens, UsageAccounting accounting) {
        this.local = local;
        this.catalog = catalog;
        this.registry = registry;
        this.health = health;
        this.router = router;
        this.failover = failover;
        this.admission = admission;
        this.guard = guard;
        this.tokens = tokens;
        this.accounting = accounting;
    }

    public String localModel() {
        return local.modelName();
    }

    public EmbeddingResult embed(Caller caller, String requestId, EmbeddingRequest request) {
        long started = System.nanoTime();
        Tenant tenant = caller.tenant();
        admission.admit(tenant);
        MaskingSession session = new MaskingSession();
        List<String> input = guard.maskAll(tenant, request.input(), session);
        long promptTokens = input.stream().mapToLong(tokens::countText).sum();
        if (isLocal(request.model())) {
            List<float[]> vectors = local.embedAll(input);
            TokenUsage usage = new TokenUsage(promptTokens, 0);
            record(caller, requestId, request.model(), "local", local.modelName(), usage, Cost.ZERO, started, 0,
                    session.total());
            return new EmbeddingResult(vectors, usage, local.modelName());
        }
        List<ProviderDescriptor> offering = catalog.all().stream()
                .filter(d -> d.offer(request.model()).isPresent())
                .filter(d -> registry.get(d.id()).map(p -> p.supportsEmbeddings()).orElse(false))
                .toList();
        if (offering.isEmpty()) {
            throw new UnknownModelException(request.model());
        }
        List<ProviderDescriptor> healthy = offering.stream()
                .filter(ProviderDescriptor::enabled)
                .filter(d -> health.breaker(d.id()).wouldPermit())
                .toList();
        if (healthy.isEmpty()) {
            throw new NoProviderAvailableException(request.model());
        }
        RouteQuery q = new RouteQuery(request.model(), promptTokens, 0, null);
        List<ProviderDescriptor> plan = router.plan(tenant.routing(), healthy, q);
        BudgetReservation reservation = admission.reserve(tenant, promptTokens, Router.estimatedCost(plan.get(0), q));
        FailoverExecutor.Served<EmbeddingResult> served;
        try {
            served = failover.execute(plan, request.model(),
                    (p, d, offer) -> p.embed(offer.nativeModel(), input, d.timeout()), FailoverExecutor.Mode.UNARY);
        } catch (RuntimeException e) {
            admission.release(reservation);
            throw e;
        }
        EmbeddingResult result = served.value();
        TokenUsage usage = result.usage() != null && result.usage().total() > 0
                ? result.usage() : new TokenUsage(promptTokens, 0);
        Cost cost = served.offer().price().cost(usage);
        admission.settle(reservation, usage.total(), cost);
        record(caller, requestId, request.model(), served.provider().id().value(), result.providerModel(), usage, cost,
                started, served.attempts().size(), session.total());
        return new EmbeddingResult(result.vectors(), usage, result.providerModel());
    }

    private boolean isLocal(String model) {
        return model.equals(local.modelName()) || model.equals("tg-embed-local");
    }

    private void record(Caller caller, String requestId, String model, String provider, String providerModel,
                        TokenUsage usage, Cost cost, long startedNanos, int attempts, int piiMasked) {
        accounting.record(new UsageAccounting.Usage(caller, requestId, model, provider, providerModel, usage, cost,
                UsageEvent.CacheOutcome.BYPASS, false, (System.nanoTime() - startedNanos) / 1_000_000,
                UsageEvent.Outcome.OK, attempts, piiMasked));
    }
}
