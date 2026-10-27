package com.tokengate.gateway.domain.routing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingTest {

    private static ConsistentHashRing<String> ring(String... nodes) {
        ConsistentHashRing<String> r = new ConsistentHashRing<>(160, Function.identity());
        for (String n : nodes) {
            r.add(n);
        }
        return r;
    }

    @Test
    void sameKeyAlwaysMapsToSameNode() {
        ConsistentHashRing<String> r = ring("a", "b", "c");
        String first = r.get("session-42").orElseThrow();
        for (int i = 0; i < 100; i++) {
            assertThat(r.get("session-42")).contains(first);
        }
    }

    @Test
    void removingANodeOnlyMovesThatNodesKeys() {
        ConsistentHashRing<String> r = ring("a", "b", "c", "d");
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            before.put("k" + i, r.get("k" + i).orElseThrow());
        }
        r.remove("c");
        int moved = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = r.get(e.getKey()).orElseThrow();
            if (!now.equals(e.getValue())) {
                moved++;
                assertThat(e.getValue()).isEqualTo("c");
            }
        }
        assertThat(moved).isBetween(1_500, 3_500);
    }

    @Test
    void virtualNodesSpreadKeysReasonablyEvenly() {
        ConsistentHashRing<String> r = ring("a", "b", "c", "d");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 40_000; i++) {
            counts.merge(r.get("user-" + i).orElseThrow(), 1, Integer::sum);
        }
        assertThat(counts.values()).allSatisfy(c -> assertThat(c).isBetween(7_000, 13_000));
    }

    @Test
    void skipsIneligibleNodesClockwiseAndWrapsAround() {
        ConsistentHashRing<String> r = ring("a", "b");
        for (int i = 0; i < 200; i++) {
            assertThat(r.get("k" + i, n -> n.equals("b"))).contains("b");
        }
        assertThat(r.get("k", n -> false)).isEmpty();
        assertThat(ring().get("k")).isEmpty();
    }
}
