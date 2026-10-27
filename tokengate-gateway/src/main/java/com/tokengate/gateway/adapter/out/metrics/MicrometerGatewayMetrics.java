package com.tokengate.gateway.adapter.out.metrics;

import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ProviderId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Tenant is a tag on tokens, cost and rejections. That is fine for tens of tenants; with thousands it should be
 * dropped from Prometheus and left to the metering service.
 */
@Component
public class MicrometerGatewayMetrics implements GatewayMetrics {

    private final MeterRegistry registry;

    public MicrometerGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void providerCall(ProviderId provider, Duration latency, String outcome) {
        Timer.builder("tokengate.provider.latency")
                .description("Upstream call latency per provider (time to first byte for streams)")
                .tag("provider", provider.value())
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .register(registry)
                .record(latency);
    }

    @Override
    public void cacheLookup(String tier, boolean hit) {
        Counter.builder("tokengate.cache.lookups")
                .tag("tier", tier)
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    @Override
    public void tokens(String tenant, String provider, long prompt, long completion) {
        tokenCounter(tenant, provider, "prompt").increment(prompt);
        tokenCounter(tenant, provider, "completion").increment(completion);
    }

    private Counter tokenCounter(String tenant, String provider, String type) {
        return Counter.builder("tokengate.tokens")
                .tag("tenant", tenant)
                .tag("provider", provider)
                .tag("type", type)
                .register(registry);
    }

    @Override
    public void cost(String tenant, String provider, Cost cost) {
        Counter.builder("tokengate.cost.usd")
                .description("Estimated provider cost from the gateway's price table")
                .tag("tenant", tenant)
                .tag("provider", provider)
                .register(registry)
                .increment(cost.usd().doubleValue());
    }

    @Override
    public void rejected(String tenant, String reason) {
        Counter.builder("tokengate.rejections").tag("tenant", tenant).tag("reason", reason)
                .register(registry).increment();
    }

    @Override
    public void failover(ProviderId from, ProviderId to) {
        Counter.builder("tokengate.failovers").tag("from", from.value()).tag("to", to.value())
                .register(registry).increment();
    }

    @Override
    public void piiMasked(String type, int count) {
        Counter.builder("tokengate.pii.masked").tag("type", type).register(registry).increment(count);
    }

    @Override
    public void streamEnded(String outcome) {
        Counter.builder("tokengate.streams").tag("outcome", outcome).register(registry).increment();
    }

    @Override
    public void usageRecordFailed() {
        Counter.builder("tokengate.usage.record.failures").register(registry).increment();
    }

    @Override
    public void trackBreaker(ProviderId provider, Supplier<CircuitBreaker.State> state) {
        Gauge.builder("tokengate.breaker.state", state, s -> s.get().ordinal())
                .description("0 closed, 1 half-open, 2 open")
                .tag("provider", provider.value())
                // the supplier is a lambda nobody else holds; with Micrometer's default weak reference it is
                // collected and the gauge reads NaN
                .strongReference(true)
                .register(registry);
    }
}
