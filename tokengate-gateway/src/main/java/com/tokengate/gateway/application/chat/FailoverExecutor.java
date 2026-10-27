package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.application.error.NoProviderAvailableException;
import com.tokengate.gateway.application.error.UpstreamFailedException;
import com.tokengate.gateway.application.port.out.ChatProvider;
import com.tokengate.gateway.application.port.out.ChatProviderRegistry;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.ModelOffer;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;
import com.tokengate.gateway.domain.routing.RetryPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

/**
 * Walks a routing plan: retries the same provider only for failures where the request provably did not run,
 * moves on for everything else, and keeps every provider's breaker and latency estimate up to date.
 */
public final class FailoverExecutor {

    @FunctionalInterface
    public interface Call<T> {
        T invoke(ChatProvider provider, ProviderDescriptor descriptor, ModelOffer offer);
    }

    /**
     * @param breaker for a stream, the success is not recorded at open time; whoever drains the stream reports
     *                the final outcome on this breaker
     */
    public record Served<T>(T value, ProviderDescriptor provider, ModelOffer offer, List<Attempt> attempts,
                            CircuitBreaker breaker) {
    }

    public enum Mode { UNARY, HEDGED, STREAM_OPEN }

    private final ChatProviderRegistry registry;
    private final ProviderHealth health;
    private final RetryPolicy retry;
    private final GatewayMetrics metrics;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final ExecutorService hedgePool;
    private final Duration hedgeAfter;

    public FailoverExecutor(ChatProviderRegistry registry, ProviderHealth health, RetryPolicy retry,
                            GatewayMetrics metrics, Sleeper sleeper, RandomGenerator random,
                            ExecutorService hedgePool, Duration hedgeAfter) {
        this.registry = registry;
        this.health = health;
        this.retry = retry;
        this.metrics = metrics;
        this.sleeper = sleeper;
        this.random = random;
        this.hedgePool = hedgePool;
        this.hedgeAfter = hedgeAfter;
    }

    /**
     * HEDGED starts the second provider if the first is still running after the hedge delay. Only for unary
     * calls, and it can mean paying for two answers.
     */
    public <T> Served<T> execute(List<ProviderDescriptor> plan, String model, Call<T> call, Mode mode) {
        boolean defer = mode == Mode.STREAM_OPEN;
        List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
        Failures failures = new Failures();
        int start = 0;
        if (mode == Mode.HEDGED && hedgeAfter != null && plan.size() >= 2) {
            Served<T> won = hedged(plan.get(0), plan.get(1), model, call, attempts, failures);
            if (won != null) {
                return won;
            }
            start = 2;
        }
        ProviderId previous = start > 0 ? plan.get(start - 1).id() : null;
        for (int i = start; i < plan.size(); i++) {
            ProviderDescriptor d = plan.get(i);
            T value = tryProvider(d, model, call, attempts, failures, false, new AtomicBoolean(), defer);
            if (value != null) {
                if (previous != null) {
                    metrics.failover(previous, d.id());
                }
                return served(value, d, model, attempts);
            }
            previous = d.id();
        }
        if (failures.last == null) {
            throw new NoProviderAvailableException(model);
        }
        throw new UpstreamFailedException(attempts, failures.last);
    }

    private <T> Served<T> hedged(ProviderDescriptor first, ProviderDescriptor second, String model, Call<T> call,
                                 List<Attempt> attempts, Failures failures) {
        AtomicBoolean cancelFirst = new AtomicBoolean();
        AtomicBoolean cancelSecond = new AtomicBoolean();
        CompletableFuture<T> a = CompletableFuture.supplyAsync(
                () -> tryProvider(first, model, call, attempts, failures, false, cancelFirst, false), hedgePool);
        T early = await(a, hedgeAfter);
        if (a.isDone()) {
            if (early != null) {
                return served(early, first, model, attempts);
            }
            T fallback = tryProvider(second, model, call, attempts, failures, false, new AtomicBoolean(), false);
            if (fallback == null) {
                return null;
            }
            metrics.failover(first.id(), second.id());
            return served(fallback, second, model, attempts);
        }
        CompletableFuture<T> b = CompletableFuture.supplyAsync(
                () -> tryProvider(second, model, call, attempts, failures, true, cancelSecond, false), hedgePool);
        T winner = firstNonNull(a, b).join();
        if (winner == null) {
            return null;
        }
        boolean firstWon = a.isDone() && !a.isCompletedExceptionally() && a.getNow(null) == winner;
        (firstWon ? cancelSecond : cancelFirst).set(true);
        (firstWon ? b : a).cancel(true);
        ProviderDescriptor servedBy = firstWon ? first : second;
        return served(winner, servedBy, model, attempts);
    }

    private <T> Served<T> served(T value, ProviderDescriptor d, String model, List<Attempt> attempts) {
        return new Served<>(value, d, d.offer(model).orElseThrow(), List.copyOf(attempts), health.breaker(d.id()));
    }

    /** Completes with the first non-null value, or with null once both have finished without one. */
    private static <T> CompletableFuture<T> firstNonNull(CompletableFuture<T> a, CompletableFuture<T> b) {
        CompletableFuture<T> race = new CompletableFuture<>();
        BiConsumer<T, Throwable> onDone = (v, e) -> {
            if (v != null) {
                race.complete(v);
            } else if (a.isDone() && b.isDone()) {
                race.complete(null);
            }
        };
        a.whenComplete(onDone);
        b.whenComplete(onDone);
        return race;
    }

    private static <T> T await(CompletableFuture<T> f, Duration d) {
        try {
            return f.get(d.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /** @return the value, or null if this provider is exhausted and the plan should move on */
    private <T> T tryProvider(ProviderDescriptor d, String model, Call<T> call, List<Attempt> attempts,
                              Failures failures, boolean hedge, AtomicBoolean cancelled, boolean deferSuccess) {
        ChatProvider provider = registry.get(d.id()).orElse(null);
        ModelOffer offer = d.offer(model).orElse(null);
        if (provider == null || offer == null) {
            return null;
        }
        CircuitBreaker breaker = health.breaker(d.id());
        for (int attempt = 1; ; attempt++) {
            if (!breaker.tryAcquire()) {
                return null;
            }
            long started = System.nanoTime();
            try {
                T value = call.invoke(provider, d, offer);
                long ms = elapsedMs(started);
                if (!deferSuccess) {
                    breaker.onSuccess();
                }
                health.recordLatency(d.id(), ms);
                metrics.providerCall(d.id(), Duration.ofMillis(ms), "ok");
                attempts.add(Attempt.ok(d.id(), ms, hedge));
                return value;
            } catch (ProviderFailure f) {
                long ms = elapsedMs(started);
                if (cancelled.get()) {
                    breaker.onIgnored();
                    attempts.add(Attempt.cancelled(d.id(), ms, hedge));
                    return null;
                }
                if (f.kind().countsAgainstBreaker()) {
                    breaker.onFailure();
                } else {
                    breaker.onIgnored();
                }
                metrics.providerCall(d.id(), Duration.ofMillis(ms), f.kind().name().toLowerCase());
                attempts.add(Attempt.failed(d.id(), ms, f.kind(), hedge));
                failures.last = f;
                if (!f.kind().failover() && !f.kind().retrySameProvider()) {
                    throw f;
                }
                if (!retry.shouldRetry(f.kind(), attempt)) {
                    return null;
                }
                if (!pause(retry.backoff(attempt, f.retryAfter().orElse(null), random))) {
                    return null;
                }
            }
        }
    }

    private boolean pause(Duration d) {
        try {
            sleeper.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static final class Failures {
        volatile ProviderFailure last;
    }
}
