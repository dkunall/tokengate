package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.Ewma;
import com.tokengate.gateway.domain.routing.ProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Breaker and latency estimate per provider, created lazily and kept for the life of the process. */
public final class ProviderHealth {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealth.class);

    private final CircuitBreaker.Config breakerConfig;
    private final Clock clock;
    private final double alpha;
    private final GatewayMetrics metrics;
    private final ConcurrentMap<ProviderId, Entry> entries = new ConcurrentHashMap<>();

    public ProviderHealth(CircuitBreaker.Config breakerConfig, Clock clock, double alpha, GatewayMetrics metrics) {
        this.breakerConfig = breakerConfig;
        this.clock = clock;
        this.alpha = alpha;
        this.metrics = metrics;
    }

    public CircuitBreaker breaker(ProviderId id) {
        return entry(id).breaker();
    }

    public void recordLatency(ProviderId id, long millis) {
        entry(id).latency().record(millis);
    }

    public OptionalDouble latencyMillis(ProviderId id) {
        Entry e = entries.get(id);
        return e == null || !e.latency().hasSamples() ? OptionalDouble.empty() : OptionalDouble.of(e.latency().valueOr(0));
    }

    public Snapshot snapshot(ProviderId id) {
        CircuitBreaker b = breaker(id);
        return new Snapshot(b.state(), b.failureRate(), latencyMillis(id));
    }

    private Entry entry(ProviderId id) {
        return entries.computeIfAbsent(id, this::create);
    }

    private Entry create(ProviderId id) {
        CircuitBreaker breaker = new CircuitBreaker(breakerConfig, clock,
                (from, to) -> log.warn("circuit breaker for {} moved {} -> {}", id, from, to));
        metrics.trackBreaker(id, breaker::state);
        return new Entry(breaker, new Ewma(alpha));
    }

    public record Snapshot(CircuitBreaker.State state, double failureRate, OptionalDouble latencyMillis) {
    }

    private record Entry(CircuitBreaker breaker, Ewma latency) {
    }
}
