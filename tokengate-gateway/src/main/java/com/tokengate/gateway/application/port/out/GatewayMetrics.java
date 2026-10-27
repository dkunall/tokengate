package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.time.Duration;
import java.util.function.Supplier;

public interface GatewayMetrics {

    void providerCall(ProviderId provider, Duration latency, String outcome);

    void cacheLookup(String tier, boolean hit);

    void tokens(String tenant, String provider, long prompt, long completion);

    void cost(String tenant, String provider, Cost cost);

    void rejected(String tenant, String reason);

    void failover(ProviderId from, ProviderId to);

    void piiMasked(String type, int count);

    void streamEnded(String outcome);

    void usageRecordFailed();

    void trackBreaker(ProviderId provider, Supplier<CircuitBreaker.State> state);
}
