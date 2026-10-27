package com.tokengate.gateway.domain.routing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTest {

    private static ProviderDescriptor provider(String id, long inPer1k, long outPer1k, int weight, int priority) {
        return new ProviderDescriptor(ProviderId.of(id), ProviderType.MOCK,
                Map.of("tg-small", new ModelOffer(id + "-small", new Price(inPer1k, outPer1k))),
                weight, priority, true, Duration.ofSeconds(5));
    }

    private final ProviderDescriptor cheap = provider("cheap", 100, 200, 1, 2);
    private final ProviderDescriptor fast = provider("fast", 500, 1_000, 3, 1);
    private final ProviderDescriptor spare = provider("spare", 300, 300, 1, 3);
    private final List<ProviderDescriptor> all = List.of(cheap, fast, spare);
    private final RouteQuery query = new RouteQuery("tg-small", 1_000, 200, null);

    private final Map<ProviderId, Double> latencies = new HashMap<>();
    private final Router router = new Router(id -> latencies.containsKey(id)
            ? OptionalDouble.of(latencies.get(id)) : OptionalDouble.empty());

    private static List<String> ids(List<ProviderDescriptor> plan) {
        return plan.stream().map(d -> d.id().value()).toList();
    }

    @Test
    void costPicksCheapestEstimate() {
        assertThat(Router.estimatedCost(cheap, query)).isEqualTo(new Cost(140));
        assertThat(ids(router.plan(RoutingPolicy.COST, all, query))).containsExactly("cheap", "spare", "fast");
    }

    @Test
    void latencyPrefersUnsampledThenLowestEwma() {
        latencies.put(cheap.id(), 300.0);
        latencies.put(fast.id(), 40.0);
        assertThat(ids(router.plan(RoutingPolicy.LATENCY, all, query))).containsExactly("spare", "fast", "cheap");
        latencies.put(spare.id(), 90.0);
        assertThat(ids(router.plan(RoutingPolicy.LATENCY, all, query))).containsExactly("fast", "spare", "cheap");
    }

    @Test
    void priorityOrderIsTheFailoverTail() {
        assertThat(ids(router.plan(RoutingPolicy.PRIORITY, all, query))).containsExactly("fast", "cheap", "spare");
    }

    @Test
    void weightedSpreadsPrimariesByWeightAndKeepsEveryoneInThePlan() {
        Map<String, Integer> primaries = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            List<ProviderDescriptor> plan = router.plan(RoutingPolicy.WEIGHTED, all, query);
            assertThat(plan).hasSize(3);
            primaries.merge(plan.getFirst().id().value(), 1, Integer::sum);
        }
        assertThat(primaries).containsEntry("fast", 300).containsEntry("cheap", 100).containsEntry("spare", 100);
    }

    @Test
    void stickyKeepsAKeyOnOneProviderAndOnlyMovesItWhenThatProviderLeaves() {
        Map<String, String> home = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            String key = "user-" + i;
            home.put(key, router.plan(RoutingPolicy.STICKY, all, new RouteQuery("tg-small", 10, 10, key)).getFirst().id().value());
        }
        List<ProviderDescriptor> withoutFast = List.of(cheap, spare);
        int moved = 0;
        for (Map.Entry<String, String> e : home.entrySet()) {
            String now = router.plan(RoutingPolicy.STICKY, withoutFast, new RouteQuery("tg-small", 10, 10, e.getKey()))
                    .getFirst().id().value();
            if (!now.equals(e.getValue())) {
                moved++;
                assertThat(e.getValue()).isEqualTo("fast");
            }
        }
        assertThat(moved).isPositive();
        assertThat(home.values()).contains("cheap", "fast", "spare");
    }

    @Test
    void emptyCandidatesGiveEmptyPlanAndUnknownModelSortsLast() {
        assertThat(router.plan(RoutingPolicy.COST, List.of(), query)).isEmpty();
        ProviderDescriptor other = new ProviderDescriptor(ProviderId.of("other"), ProviderType.MOCK,
                Map.of("tg-large", new ModelOffer("x", new Price(1, 1))), 1, 0, true, Duration.ofSeconds(1));
        assertThat(ids(router.plan(RoutingPolicy.COST, List.of(other, cheap), query))).containsExactly("cheap", "other");
    }

    @Test
    void offerMatchesLogicalOrNativeModel() {
        assertThat(cheap.offer("tg-small")).isPresent();
        assertThat(cheap.offer("cheap-small")).isPresent();
        assertThat(cheap.offer("gpt-4o")).isEmpty();
    }
}
