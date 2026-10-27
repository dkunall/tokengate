package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.error.BudgetExceededException;
import com.tokengate.gateway.application.error.RateLimitedException;
import com.tokengate.gateway.application.error.UnknownModelException;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.pii.PiiMode;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatServiceTest {

    private final Harness h = new Harness();
    private final ChatService service = h.service();

    @Test
    void cost_policy_sends_the_call_to_the_cheaper_provider_and_records_usage() {
        ChatResult r = service.complete(Harness.caller(), "req-1", Harness.ask("what is a bloom filter", 0.0, false));

        assertThat(r.provider()).isEqualTo("alpha");
        assertThat(r.completion().content()).isEqualTo("[alpha] what is a bloom filter");
        assertThat(r.cache()).isEqualTo(UsageEvent.CacheOutcome.MISS);
        assertThat(h.beta.calls).isEmpty();
        UsageEvent e = h.recorder.last();
        assertThat(e.provider()).isEqualTo("alpha");
        assertThat(e.promptTokens()).isEqualTo(10);
        assertThat(e.completionTokens()).isEqualTo(5);
        assertThat(h.ledger.settled).containsExactly(15L);
        assertThat(h.recorder.entries.get(0).estimatedCostMicros()).isEqualTo(2);
    }

    @Test
    void identical_deterministic_request_is_served_from_l1_then_from_the_shared_exact_cache() {
        ChatRequest q = Harness.ask("explain consistent hashing", 0.0, false);
        service.complete(Harness.caller(), "r1", q);

        ChatResult l1 = service.complete(Harness.caller(), "r2", q);
        h.l1.clear();
        ChatResult exact = service.complete(Harness.caller(), "r3", q);

        assertThat(h.alpha.calls).hasSize(1);
        assertThat(l1.cache()).isEqualTo(UsageEvent.CacheOutcome.L1_HIT);
        assertThat(exact.cache()).isEqualTo(UsageEvent.CacheOutcome.EXACT_HIT);
        assertThat(exact.completion().content()).isEqualTo("[alpha] explain consistent hashing");
        assertThat(h.recorder.last().provider()).isNull();
    }

    @Test
    void a_paraphrase_hits_the_semantic_cache() {
        service.complete(Harness.caller(), "r1", Harness.ask("explain consistent hashing", 0.0, false));
        h.l1.clear();

        ChatResult r = service.complete(Harness.caller(), "r2",
                Harness.ask("please explain the consistent hashing", 0.0, false));

        assertThat(r.cache()).isEqualTo(UsageEvent.CacheOutcome.SEMANTIC_HIT);
        assertThat(r.similarity()).isGreaterThanOrEqualTo(0.9);
        assertThat(h.alpha.calls).hasSize(1);
    }

    @Test
    void non_deterministic_requests_bypass_the_cache() {
        service.complete(Harness.caller(), "r1", Harness.ask("tell me a joke", 0.7, false));
        ChatResult second = service.complete(Harness.caller(), "r2", Harness.ask("tell me a joke", 0.7, false));

        assertThat(second.cache()).isEqualTo(UsageEvent.CacheOutcome.BYPASS);
        assertThat(h.alpha.calls).hasSize(2);
        assertThat(h.exact.map).isEmpty();
    }

    @Test
    void server_error_fails_over_to_the_next_provider() {
        h.alpha.failNext(FailureKind.SERVER_ERROR);

        ChatResult r = service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false));

        assertThat(r.provider()).isEqualTo("beta");
        assertThat(r.attempts()).extracting(Attempt::describe).hasSize(2);
        assertThat(h.metrics.failovers).containsExactly("alpha->beta");
        assertThat(h.recorder.last().provider()).isEqualTo("beta");
    }

    @Test
    void model_missing_at_one_provider_fails_over_without_a_retry() {
        h.alpha.failNext(FailureKind.NOT_FOUND);

        ChatResult r = service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false));

        assertThat(r.provider()).isEqualTo("beta");
        assertThat(h.alpha.calls).hasSize(1);
        assertThat(r.attempts()).extracting(Attempt::describe).containsExactly("alpha:not_found", "beta:ok");
    }

    @Test
    void bad_request_is_neither_retried_nor_failed_over_and_the_reservation_is_released() {
        h.alpha.failNext(FailureKind.BAD_REQUEST);

        assertThatThrownBy(() -> service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false)))
                .isInstanceOf(ProviderFailure.class);
        assertThat(h.alpha.calls).hasSize(1);
        assertThat(h.beta.calls).isEmpty();
        assertThat(h.ledger.released).hasSize(1);
        assertThat(h.ledger.tokens.get("acme")).isZero();
    }

    @Test
    void pii_is_masked_on_the_way_out_and_restored_for_the_caller() {
        ChatResult r = service.complete(Harness.caller(), "r1",
                Harness.ask("mail kunal@example.com about card 4111 1111 1111 1111", 0.0, false));

        String sent = h.alpha.calls.get(0).request().lastUserMessage().content();
        assertThat(sent).doesNotContain("kunal@example.com").doesNotContain("4111")
                .contains("<EMAIL_").contains("<CARD_");
        assertThat(r.completion().content()).contains("kunal@example.com").contains("4111 1111 1111 1111");
        assertThat(r.piiMasked()).isEqualTo(2);
        assertThat(h.exact.map.values()).allSatisfy(c -> assertThat(c.content()).doesNotContain("kunal@"));
    }

    @Test
    void redact_mode_does_not_restore_the_values() {
        Caller c = new Caller(Harness.tenant(PiiMode.REDACT, 1_000_000), "k");
        ChatResult r = service.complete(c, "r1", Harness.ask("call +91 98765 43210", 0.0, false));

        assertThat(h.alpha.calls.get(0).request().lastUserMessage().content()).doesNotContain("98765");
        assertThat(r.completion().content()).doesNotContain("98765");
    }

    @Test
    void rate_limited_request_never_reaches_a_provider() {
        h.limiter.deny = true;

        assertThatThrownBy(() -> service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false)))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.decision().retryAfterSeconds()).isEqualTo(3));
        assertThat(h.alpha.calls).isEmpty();
    }

    @Test
    void limiter_outage_fails_open() {
        h.limiter.broken = true;

        assertThat(service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false)).provider())
                .isEqualTo("alpha");
    }

    @Test
    void exhausted_daily_budget_rejects_before_routing() {
        Caller poor = new Caller(Harness.tenant(PiiMode.OFF, 20), "k");

        assertThatThrownBy(() -> service.complete(poor, "r1", Harness.ask("hi there", 0.0, false)))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(h.alpha.calls).isEmpty();
    }

    @Test
    void unknown_model_is_reported_as_such() {
        ChatRequest r = new ChatRequest("gpt-9", List.of(ChatMessage.user("hi")), 0.0, null, null, null, false, null);

        assertThatThrownBy(() -> service.complete(Harness.caller(), "r1", r)).isInstanceOf(UnknownModelException.class);
    }

    @Test
    void a_failed_usage_write_does_not_fail_the_answered_request() {
        h.recorder.broken = true;

        ChatResult r = service.complete(Harness.caller(), "r1", Harness.ask("hi", 0.0, false));

        assertThat(r.completion().content()).isEqualTo("[alpha] hi");
        assertThat(h.metrics.recordFailures).hasValue(1);
    }
}
