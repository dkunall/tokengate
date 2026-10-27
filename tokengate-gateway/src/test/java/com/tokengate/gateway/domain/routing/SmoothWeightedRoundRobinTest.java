package com.tokengate.gateway.domain.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmoothWeightedRoundRobinTest {

    private static final Map<String, Integer> WEIGHTS = Map.of("a", 5, "b", 1, "c", 1);

    @Test
    void producesNginxSmoothSequence() {
        SmoothWeightedRoundRobin<String> wrr = new SmoothWeightedRoundRobin<>();
        List<String> seq = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seq.add(wrr.pick(List.of("a", "b", "c"), WEIGHTS::get));
        }
        assertThat(seq).containsExactly("a", "a", "b", "a", "c", "a", "a");
    }

    @Test
    void distributionMatchesWeightsExactlyPerCycle() {
        SmoothWeightedRoundRobin<String> wrr = new SmoothWeightedRoundRobin<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 700; i++) {
            counts.merge(wrr.pick(List.of("a", "b", "c"), WEIGHTS::get), 1, Integer::sum);
        }
        assertThat(counts).containsEntry("a", 500).containsEntry("b", 100).containsEntry("c", 100);
    }

    @Test
    void ineligibleMemberSitsOutAndZeroWeightIsNeverPicked() {
        SmoothWeightedRoundRobin<String> wrr = new SmoothWeightedRoundRobin<>();
        for (int i = 0; i < 20; i++) {
            assertThat(wrr.pick(List.of("b", "c"), WEIGHTS::get)).isIn("b", "c");
        }
        Map<String, Integer> w = Map.of("x", 0, "y", 3);
        for (int i = 0; i < 10; i++) {
            assertThat(wrr.pick(List.of("x", "y"), w::get)).isEqualTo("y");
        }
    }
}
