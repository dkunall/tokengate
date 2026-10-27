package com.tokengate.gateway.domain.pii;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AhoCorasickTest {

    @Test
    void findsAllOverlappingTermsInOnePass() {
        AhoCorasick ac = new AhoCorasick(List.of("he", "she", "his", "hers"));
        assertThat(ac.findAll("ushers")).extracting(AhoCorasick.Hit::term)
                .containsExactlyInAnyOrder("she", "he", "hers");
        assertThat(ac.findAll("ushers")).contains(new AhoCorasick.Hit(1, 4, "she"), new AhoCorasick.Hit(2, 6, "hers"));
    }

    @Test
    void isCaseInsensitiveAndHandlesEmptyInput() {
        AhoCorasick ac = new AhoCorasick(List.of("Project Falcon"));
        assertThat(ac.findAll("status of PROJECT FALCON?")).singleElement()
                .satisfies(h -> assertThat(h.start()).isEqualTo(10));
        assertThat(ac.findAll("")).isEmpty();
        assertThat(new AhoCorasick(List.of()).isEmpty()).isTrue();
    }
}
