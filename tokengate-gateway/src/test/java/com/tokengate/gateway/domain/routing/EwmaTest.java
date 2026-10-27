package com.tokengate.gateway.domain.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EwmaTest {

    @Test
    void firstSampleSeedsTheAverage() {
        Ewma e = new Ewma(0.2);
        assertThat(e.hasSamples()).isFalse();
        assertThat(e.valueOr(-1)).isEqualTo(-1);
        e.record(100);
        assertThat(e.valueOr(-1)).isEqualTo(100);
        e.record(200);
        assertThat(e.valueOr(-1)).isCloseTo(120, within(1e-9));
    }

    @Test
    void convergesTowardsANewLevel() {
        Ewma e = new Ewma(0.3);
        e.record(500);
        for (int i = 0; i < 30; i++) {
            e.record(50);
        }
        assertThat(e.valueOr(0)).isCloseTo(50, within(1.0));
    }

    @Test
    void concurrentSamplesOfOneValueStayAtThatValue() throws Exception {
        Ewma e = new Ewma(0.5);
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < 10_000; j++) {
                    e.record(42);
                }
            });
        }
        for (Thread t : ts) {
            t.join();
        }
        assertThat(e.valueOr(0)).isEqualTo(42);
    }
}
