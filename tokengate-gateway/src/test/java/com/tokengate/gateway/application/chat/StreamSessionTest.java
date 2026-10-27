package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.domain.routing.FailureKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSessionTest {

    private final Harness h = new Harness();
    private final ChatService service = h.service();

    @Test
    void streams_chunks_then_finishes_and_bills_counted_tokens() {
        h.alpha.streamNext("one ", "two ", "three");
        Fakes.RecordingSink sink = new Fakes.RecordingSink();

        StreamSession s = service.openStream(Harness.caller(), "s1", Harness.ask("count", 0.7, true));
        s.pump(sink);

        assertThat(sink.text()).isEqualTo("one two three");
        assertThat(sink.finish).isEqualTo("stop");
        UsageEvent e = h.recorder.last();
        assertThat(e.streamed()).isTrue();
        assertThat(e.completionTokens()).isEqualTo(3);
        assertThat(e.outcome()).isEqualTo(UsageEvent.Outcome.OK);
        assertThat(h.health.breaker(h.alpha.id).failureRate()).isZero();
    }

    @Test
    void provider_refusing_to_open_is_failed_over_before_the_response_starts() {
        h.alpha.streamNext(FailureKind.SERVER_ERROR);
        Fakes.RecordingSink sink = new Fakes.RecordingSink();

        StreamSession s = service.openStream(Harness.caller(), "s1", Harness.ask("hi", 0.7, true));
        s.pump(sink);

        assertThat(s.provider()).isEqualTo("beta");
        assertThat(sink.text()).isEqualTo("hello from beta");
    }

    @Test
    void failure_before_any_output_continues_on_the_next_provider() {
        h.alpha.streamNext("", FailureKind.MIDSTREAM);
        h.beta.streamNext("rescued");
        Fakes.RecordingSink sink = new Fakes.RecordingSink();

        StreamSession s = service.openStream(Harness.caller(), "s1", Harness.ask("hi", 0.7, true));
        s.pump(sink);

        assertThat(sink.text()).isEqualTo("rescued");
        assertThat(sink.errorType).isNull();
        assertThat(h.recorder.last().provider()).isEqualTo("beta");
        assertThat(h.metrics.failovers).contains("alpha->beta");
    }

    @Test
    void failure_after_output_ends_with_an_error_event_and_bills_what_was_sent() {
        h.alpha.streamNext("partial ", "answer ", FailureKind.MIDSTREAM);
        Fakes.RecordingSink sink = new Fakes.RecordingSink();

        service.openStream(Harness.caller(), "s1", Harness.ask("hi", 0.7, true)).pump(sink);

        assertThat(sink.text()).isEqualTo("partial answer ");
        assertThat(sink.errorType).isEqualTo("upstream_error");
        assertThat(sink.finish).isNull();
        assertThat(h.beta.calls).isEmpty();
        UsageEvent e = h.recorder.last();
        assertThat(e.outcome()).isEqualTo(UsageEvent.Outcome.PROVIDER_FAILED_MIDSTREAM);
        assertThat(e.completionTokens()).isEqualTo(2);
    }

    @Test
    void client_disconnect_closes_the_upstream_and_records_a_cancel() {
        h.alpha.streamNext("a ", "b ", "c ", "d");
        Fakes.RecordingSink sink = new Fakes.RecordingSink();
        sink.failAfterDeltas = 1;

        service.openStream(Harness.caller(), "s1", Harness.ask("hi", 0.7, true)).pump(sink);

        assertThat(h.alpha.closedStreams).hasValue(1);
        assertThat(h.recorder.last().outcome()).isEqualTo(UsageEvent.Outcome.CLIENT_CANCELLED);
        assertThat(h.metrics.streamOutcomes).containsExactly("client_cancelled");
        assertThat(h.health.breaker(h.alpha.id).failureRate()).isZero();
    }

    @Test
    void pii_tokens_split_across_chunks_are_restored() {
        StreamSession first = service.openStream(Harness.caller(), "s0",
                Harness.ask("write to kunal@example.com", 0.7, true));
        String token = h.alpha.calls.get(0).request().lastUserMessage().content().replace("write to ", "");
        first.pump(new Fakes.RecordingSink());
        h.alpha.streamNext("sent to " + token.substring(0, 4), token.substring(4), " done");
        Fakes.RecordingSink sink = new Fakes.RecordingSink();

        service.openStream(Harness.caller(), "s1", Harness.ask("write to kunal@example.com", 0.7, true)).pump(sink);

        assertThat(sink.text()).isEqualTo("sent to kunal@example.com done");
    }

    @Test
    void cacheable_stream_is_stored_only_when_the_tenant_opts_in() {
        h.alpha.streamNext("cached answer");
        service.openStream(Harness.caller(), "s1", Harness.ask("q", 0.0, true)).pump(new Fakes.RecordingSink());

        assertThat(h.exact.map).isEmpty();
    }
}
