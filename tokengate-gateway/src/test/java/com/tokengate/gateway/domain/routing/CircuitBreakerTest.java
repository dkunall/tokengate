package com.tokengate.gateway.domain.routing;

import com.tokengate.gateway.domain.routing.CircuitBreaker.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final List<String> transitions = new ArrayList<>();
    private final CircuitBreaker breaker = new CircuitBreaker(
            new CircuitBreaker.Config(10, 4, 0.5, Duration.ofSeconds(5), 2), clock,
            (from, to) -> transitions.add(from + "->" + to));

    private void fail(int n) {
        for (int i = 0; i < n; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onFailure();
        }
    }

    private void succeed(int n) {
        for (int i = 0; i < n; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onSuccess();
        }
    }

    @Test
    void staysClosedBelowMinimumCalls() {
        fail(3);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void opensWhenFailureRateReachesThreshold() {
        succeed(2);
        fail(1);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        fail(1);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.wouldPermit()).isFalse();
        assertThat(transitions).containsExactly("CLOSED->OPEN");
    }

    @Test
    void slidingWindowForgetsOldFailures() {
        fail(1);
        succeed(9);
        succeed(5);
        fail(4);
        assertThat(breaker.failureRate()).isEqualTo(0.4);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void halfOpenAllowsLimitedTrialsAndClosesAfterAllSucceed() {
        fail(4);
        clock.advance(Duration.ofSeconds(5));
        assertThat(breaker.wouldPermit()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).isFalse();
        breaker.onSuccess();
        assertThat(breaker.tryAcquire()).isFalse();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.failureRate()).isZero();
        assertThat(transitions).containsExactly("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED");
    }

    @Test
    void anyFailureInHalfOpenReopensForAnotherCoolDown() {
        fail(4);
        clock.advance(Duration.ofSeconds(6));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        clock.advance(Duration.ofSeconds(4));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void ignoredOutcomeReleasesTheTrialPermit() {
        fail(4);
        clock.advance(Duration.ofSeconds(5));
        breaker.tryAcquire();
        breaker.tryAcquire();
        assertThat(breaker.tryAcquire()).isFalse();
        breaker.onIgnored();
        assertThat(breaker.tryAcquire()).isTrue();
    }
}
