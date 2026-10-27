package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.chat.FailoverExecutor.Mode;
import com.tokengate.gateway.application.chat.FailoverExecutor.Served;
import com.tokengate.gateway.application.error.GatewayException;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.pii.MaskingSession;
import com.tokengate.gateway.domain.pii.StreamingUnmasker;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains one upstream stream into the client. Failure semantics:
 * <ul>
 *   <li>upstream fails before any content reached the client: the next provider in the plan is tried, the
 *       client never notices</li>
 *   <li>upstream fails after content was sent: an SSE error event, no [DONE]; tokens streamed so far are billed</li>
 *   <li>client disconnects: the upstream connection is closed at the next write; tokens so far are billed</li>
 * </ul>
 * Not thread-safe: one session is pumped by one thread.
 */
public final class StreamSession {

    private static final Logger log = LoggerFactory.getLogger(StreamSession.class);

    record Context(Caller caller, String requestId, String model, ChatRequest masked, MaskingSession pii,
                   long promptTokens, RateLimitDecision rateLimit, ResponseCache.Lookup lookup, boolean cacheable,
                   long startedNanos) {
    }

    record Deps(FailoverExecutor failover, Admission admission, ResponseCache cache, TokenCounter tokens,
                UsageAccounting accounting, GatewayMetrics metrics, Clock clock) {
    }

    private final Context ctx;
    private final Deps deps;
    private final List<ProviderDescriptor> plan;
    private final BudgetReservation reservation;
    private final List<Attempt> attempts = new ArrayList<>();
    private Served<ChunkStream> current;

    private StreamSession(Context ctx, Deps deps, List<ProviderDescriptor> plan, BudgetReservation reservation,
                          Served<ChunkStream> opened) {
        this.ctx = ctx;
        this.deps = deps;
        this.plan = plan;
        this.reservation = reservation;
        this.current = opened;
        if (opened != null) {
            attempts.addAll(opened.attempts());
        }
    }

    static StreamSession live(Context ctx, Deps deps, List<ProviderDescriptor> plan, BudgetReservation reservation,
                              Served<ChunkStream> opened) {
        return new StreamSession(ctx, deps, plan, reservation, opened);
    }

    static StreamSession fromCache(Context ctx, Deps deps) {
        return new StreamSession(ctx, deps, List.of(), null, null);
    }

    public String requestId() {
        return ctx.requestId();
    }

    public String model() {
        return ctx.model();
    }

    /** null when replayed from the cache */
    public String provider() {
        return current == null ? null : current.provider().id().value();
    }

    public UsageEvent.CacheOutcome cache() {
        return ctx.lookup().outcome();
    }

    public RateLimitDecision rateLimit() {
        return ctx.rateLimit();
    }

    public int piiMasked() {
        return ctx.pii().total();
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public void pump(StreamSink sink) {
        if (current == null) {
            replayCached(sink);
            return;
        }
        StreamingUnmasker unmasker = ctx.pii().streaming();
        StringBuilder maskedText = new StringBuilder();
        long countedCompletion = 0;
        TokenUsage reported = null;
        String finish = null;
        boolean sentAny = false;
        UsageEvent.Outcome outcome = UsageEvent.Outcome.OK;
        try {
            while (true) {
                StreamDelta d;
                try {
                    d = current.value().next();
                } catch (ProviderFailure f) {
                    current.breaker().onFailure();
                    closeQuietly(current.value());
                    if (!sentAny && maskedText.isEmpty() && reopenOnNext(f)) {
                        continue;
                    }
                    log.warn("stream {} from {} failed after {} chars: {}", ctx.requestId(), provider(),
                            maskedText.length(), f.getMessage());
                    outcome = UsageEvent.Outcome.PROVIDER_FAILED_MIDSTREAM;
                    emitTail(sink, unmasker);
                    sink.error("upstream_error", "the upstream provider failed mid-stream");
                    break;
                }
                if (d == null) {
                    current.breaker().onSuccess();
                    break;
                }
                if (d.usage() != null) {
                    reported = d.usage();
                }
                if (d.finishReason() != null) {
                    finish = d.finishReason();
                }
                if (d.hasContent()) {
                    maskedText.append(d.content());
                    countedCompletion += deps.tokens().countText(d.content());
                    String ready = unmasker.feed(d.content());
                    if (!ready.isEmpty()) {
                        sink.delta(ready);
                        sentAny = true;
                    }
                }
            }
            if (outcome == UsageEvent.Outcome.OK) {
                emitTail(sink, unmasker);
                sink.finish(finish == null ? "stop" : finish, usage(reported, countedCompletion));
            }
        } catch (IOException clientGone) {
            outcome = UsageEvent.Outcome.CLIENT_CANCELLED;
            current.breaker().onIgnored();
            closeQuietly(current.value());
            log.info("client went away during stream {}, closed upstream {}", ctx.requestId(), provider());
        }
        finishAccounting(outcome, usage(reported, countedCompletion), maskedText.toString(), finish);
    }

    private boolean reopenOnNext(ProviderFailure cause) {
        int at = plan.indexOf(current.provider());
        List<ProviderDescriptor> rest = at < 0 ? List.of() : plan.subList(at + 1, plan.size());
        if (rest.isEmpty()) {
            return false;
        }
        try {
            Served<ChunkStream> next = deps.failover().execute(rest, ctx.masked().model(),
                    (p, d, offer) -> p.openStream(new ProviderCall(ctx.masked(), offer.nativeModel(), d.timeout(),
                            ctx.requestId())), Mode.STREAM_OPEN);
            log.info("stream {} failed on {} before any output ({}), continuing on {}", ctx.requestId(), provider(),
                    cause.kind(), next.provider().id());
            deps.metrics().failover(current.provider().id(), next.provider().id());
            current = next;
            attempts.addAll(next.attempts());
            return true;
        } catch (GatewayException | ProviderFailure e) {
            return false;
        }
    }

    private void replayCached(StreamSink sink) {
        CachedCompletion c = ctx.lookup().hit();
        UsageEvent.Outcome outcome = UsageEvent.Outcome.OK;
        try {
            sink.delta(ctx.pii().unmask(c.content()));
            sink.finish(c.finishReason(), c.usage());
        } catch (IOException clientGone) {
            outcome = UsageEvent.Outcome.CLIENT_CANCELLED;
        }
        deps.accounting().record(new UsageAccounting.Usage(ctx.caller(), ctx.requestId(), ctx.model(), null,
                c.providerModel(), c.usage(), Cost.ZERO, ctx.lookup().outcome(), true,
                ChatService.sinceMs(ctx.startedNanos()), outcome, 0, piiMasked()));
        deps.metrics().streamEnded(outcome.name().toLowerCase());
    }

    private static void emitTail(StreamSink sink, StreamingUnmasker unmasker) throws IOException {
        String tail = unmasker.finish();
        if (!tail.isEmpty()) {
            sink.delta(tail);
        }
    }

    private TokenUsage usage(TokenUsage reported, long countedCompletion) {
        if (reported != null && reported.total() > 0) {
            return reported;
        }
        return new TokenUsage(ctx.promptTokens(), countedCompletion);
    }

    private void finishAccounting(UsageEvent.Outcome outcome, TokenUsage usage, String maskedText, String finish) {
        Cost cost = current.offer().price().cost(usage);
        deps.admission().settle(reservation, usage.total(), cost);
        log.debug("stream usage {} {}: prompt estimate {} vs reported {}, completion {}, cost {} micros",
                current.provider().id().value(), current.offer().nativeModel(), ctx.promptTokens(),
                usage.promptTokens(), usage.completionTokens(), cost.micros());
        String providerId = current.provider().id().value();
        if (outcome == UsageEvent.Outcome.OK && ctx.cacheable()) {
            deps.cache().store(ctx.caller().tenantId(), ctx.masked(), ctx.caller().tenant().cache(), ctx.lookup(),
                    new CachedCompletion(maskedText, finish == null ? "stop" : finish, usage, providerId,
                            current.offer().nativeModel(), deps.clock().instant()));
        }
        deps.accounting().record(new UsageAccounting.Usage(ctx.caller(), ctx.requestId(), ctx.model(), providerId,
                current.offer().nativeModel(), usage, cost, ctx.lookup().outcome(), true,
                ChatService.sinceMs(ctx.startedNanos()), outcome, attempts.size(), piiMasked()));
        deps.metrics().streamEnded(outcome.name().toLowerCase());
    }

    private static void closeQuietly(ChunkStream s) {
        try {
            s.close();
        } catch (RuntimeException ignored) {
            // already broken; nothing left to release
        }
    }
}
