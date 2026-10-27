package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.application.error.NoProviderAvailableException;
import com.tokengate.gateway.application.error.UpstreamFailedException;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverExecutorTest {

    private final Harness h = new Harness();
    private final List<ProviderDescriptor> plan = h.catalog.all();

    private FailoverExecutor.Served<ChatCompletion> run(FailoverExecutor f, FailoverExecutor.Mode mode) {
        return f.execute(plan, Harness.MODEL, (p, d, offer) -> p.complete(
                new ProviderCall(Harness.ask("hi", 0.0, false), offer.nativeModel(), d.timeout(), "r")), mode);
    }

    @Test
    void upstream_429_is_retried_on_the_same_provider_after_a_backoff() {
        h.alpha.failNext(FailureKind.RATE_LIMITED);

        FailoverExecutor.Served<ChatCompletion> s = run(h.failover(null), FailoverExecutor.Mode.UNARY);

        assertThat(s.provider().id().value()).isEqualTo("alpha");
        assertThat(h.alpha.calls).hasSize(2);
        assertThat(h.sleeps).hasSize(1);
        assertThat(s.attempts()).extracting(Attempt::result).containsExactly(Attempt.Result.FAILED, Attempt.Result.OK);
    }

    @Test
    void a_timeout_is_not_retried_on_the_same_provider() {
        h.alpha.failNext(FailureKind.TIMEOUT);

        FailoverExecutor.Served<ChatCompletion> s = run(h.failover(null), FailoverExecutor.Mode.UNARY);

        assertThat(s.provider().id().value()).isEqualTo("beta");
        assertThat(h.alpha.calls).hasSize(1);
        assertThat(h.sleeps).isEmpty();
    }

    @Test
    void retries_stop_at_the_per_provider_limit_then_the_plan_moves_on() {
        h.alpha.failNext(FailureKind.UNAVAILABLE).failNext(FailureKind.UNAVAILABLE).failNext(FailureKind.UNAVAILABLE);

        FailoverExecutor.Served<ChatCompletion> s = run(h.failover(null), FailoverExecutor.Mode.UNARY);

        assertThat(h.alpha.calls).hasSize(2);
        assertThat(s.provider().id().value()).isEqualTo("beta");
    }

    @Test
    void everything_failing_surfaces_every_attempt() {
        h.alpha.failNext(FailureKind.SERVER_ERROR);
        h.beta.failNext(FailureKind.AUTH);

        assertThatThrownBy(() -> run(h.failover(null), FailoverExecutor.Mode.UNARY))
                .isInstanceOfSatisfying(UpstreamFailedException.class, e -> {
                    assertThat(e.attempts()).hasSize(2);
                    assertThat(e.last().kind()).isEqualTo(FailureKind.AUTH);
                });
    }

    @Test
    void open_breakers_are_skipped_without_calling_the_provider() {
        for (ProviderDescriptor d : plan) {
            for (int i = 0; i < 20; i++) {
                h.health.breaker(d.id()).tryAcquire();
                h.health.breaker(d.id()).onFailure();
            }
        }

        assertThatThrownBy(() -> run(h.failover(null), FailoverExecutor.Mode.UNARY))
                .isInstanceOf(NoProviderAvailableException.class);
        assertThat(h.alpha.calls).isEmpty();
        assertThat(h.beta.calls).isEmpty();
    }

    @Test
    void hedged_call_takes_the_faster_answer_and_cancels_the_slow_one() {
        h.alpha.delayMillis = 1_000;

        long started = System.nanoTime();
        FailoverExecutor.Served<ChatCompletion> s = run(h.failover(Duration.ofMillis(50)), FailoverExecutor.Mode.HEDGED);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(s.provider().id().value()).isEqualTo("beta");
        assertThat(tookMs).isLessThan(800);
        assertThat(s.attempts()).anySatisfy(a -> assertThat(a.hedge()).isTrue());
    }

    @Test
    void hedge_is_not_started_when_the_first_answer_is_quick() {
        FailoverExecutor.Served<ChatCompletion> s = run(h.failover(Duration.ofMillis(500)), FailoverExecutor.Mode.HEDGED);

        assertThat(s.provider().id().value()).isEqualTo("alpha");
        assertThat(h.beta.calls).isEmpty();
    }
}
