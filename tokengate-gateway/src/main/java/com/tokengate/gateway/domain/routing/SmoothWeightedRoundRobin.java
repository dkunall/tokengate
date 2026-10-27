package com.tokengate.gateway.domain.routing;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * nginx's smooth weighted round robin. Every pick adds each member's weight to its running score, takes the
 * highest, then subtracts the total from the winner. Weights 5,1,1 give a,a,b,a,c,a,a rather than
 * a,a,a,a,a,b,c, so a heavy member never gets a burst.
 *
 * <p>Only the members passed to {@link #pick} take part in a round, so a provider whose breaker is open
 * simply sits out and keeps its score for when it comes back.
 */
public final class SmoothWeightedRoundRobin<T> {

    private final Map<T, Long> current = new HashMap<>();

    public synchronized T pick(Collection<T> eligible, ToIntFunction<T> weight) {
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("nothing to pick from");
        }
        long total = 0;
        T best = null;
        long bestScore = Long.MIN_VALUE;
        for (T member : eligible) {
            int w = weight.applyAsInt(member);
            if (w <= 0) {
                continue;
            }
            total += w;
            long score = current.merge(member, (long) w, Long::sum);
            if (score > bestScore) {
                bestScore = score;
                best = member;
            }
        }
        if (best == null) {
            return eligible.iterator().next();
        }
        current.merge(best, -total, Long::sum);
        return best;
    }
}
