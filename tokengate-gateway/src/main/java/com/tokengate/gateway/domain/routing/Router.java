package com.tokengate.gateway.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Turns a policy and the healthy candidates into an ordered attempt list: element 0 is the primary, the rest
 * are failover targets in order. Every policy falls back to priority order for the tail so failover is
 * predictable.
 */
public final class Router {

    private final Function<ProviderId, OptionalDouble> latencyMillis;
    private final SmoothWeightedRoundRobin<ProviderId> wrr = new SmoothWeightedRoundRobin<>();
    private final ConsistentHashRing<ProviderId> ring = new ConsistentHashRing<>(128, ProviderId::value);
    private final List<ProviderId> ringMembers = new ArrayList<>();

    public Router(Function<ProviderId, OptionalDouble> latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    public List<ProviderDescriptor> plan(RoutingPolicy policy, List<ProviderDescriptor> candidates, RouteQuery q) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ProviderDescriptor> byPriority = candidates.stream().sorted(PRIORITY_ORDER).toList();
        return switch (policy) {
            case PRIORITY -> byPriority;
            case COST -> candidates.stream()
                    .sorted(Comparator.comparing((ProviderDescriptor d) -> estimatedCost(d, q)).thenComparing(PRIORITY_ORDER))
                    .toList();
            case LATENCY -> candidates.stream()
                    .sorted(Comparator.comparingDouble((ProviderDescriptor d) -> latencyRank(d)).thenComparing(PRIORITY_ORDER))
                    .toList();
            case WEIGHTED -> primaryFirst(byPriority, pickWeighted(candidates));
            case STICKY -> q.stickyKey() == null || q.stickyKey().isBlank()
                    ? primaryFirst(byPriority, pickWeighted(candidates))
                    : primaryFirst(byPriority, pickSticky(candidates, q.stickyKey()));
        };
    }

    public static Cost estimatedCost(ProviderDescriptor d, RouteQuery q) {
        return d.offer(q.model()).map(o -> o.price().cost(q.estimatedPromptTokens(), q.expectedCompletionTokens()))
                .orElse(new Cost(Long.MAX_VALUE));
    }

    /** Unsampled providers rank first so a new or recovered provider gets measured instead of starved. */
    private double latencyRank(ProviderDescriptor d) {
        return latencyMillis.apply(d.id()).orElse(-1);
    }

    private ProviderDescriptor pickWeighted(List<ProviderDescriptor> candidates) {
        ProviderId chosen = wrr.pick(candidates.stream().map(ProviderDescriptor::id).toList(),
                id -> candidates.stream().filter(c -> c.id().equals(id)).findFirst().map(ProviderDescriptor::weight).orElse(0));
        return candidates.stream().filter(c -> c.id().equals(chosen)).findFirst().orElseThrow();
    }

    private ProviderDescriptor pickSticky(List<ProviderDescriptor> candidates, String key) {
        syncRing(candidates);
        List<ProviderId> ids = candidates.stream().map(ProviderDescriptor::id).toList();
        ProviderId chosen = ring.get(key, ids::contains).orElse(ids.getFirst());
        return candidates.stream().filter(c -> c.id().equals(chosen)).findFirst().orElseThrow();
    }

    /**
     * The ring holds every provider ever seen, healthy or not; unhealthy ones are skipped at lookup time. That way
     * a breaker opening only moves the sessions that were on that provider.
     */
    private synchronized void syncRing(List<ProviderDescriptor> candidates) {
        for (ProviderDescriptor d : candidates) {
            if (!ringMembers.contains(d.id())) {
                ringMembers.add(d.id());
                ring.add(d.id());
            }
        }
    }

    private static List<ProviderDescriptor> primaryFirst(List<ProviderDescriptor> ordered, ProviderDescriptor primary) {
        List<ProviderDescriptor> out = new ArrayList<>(ordered.size());
        out.add(primary);
        ordered.stream().filter(d -> !d.id().equals(primary.id())).forEach(out::add);
        return out;
    }

    private static final Comparator<ProviderDescriptor> PRIORITY_ORDER = Comparator
            .comparingInt(ProviderDescriptor::priority)
            .thenComparing(Comparator.comparingInt(ProviderDescriptor::weight).reversed())
            .thenComparing(ProviderDescriptor::id);
}
