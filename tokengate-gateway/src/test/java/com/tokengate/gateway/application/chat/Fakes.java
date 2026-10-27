package com.tokengate.gateway.application.chat;

import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.port.out.BudgetLedger;
import com.tokengate.gateway.application.port.out.ChatProvider;
import com.tokengate.gateway.application.port.out.ChatProviderRegistry;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.application.port.out.ExactCacheStore;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.application.port.out.ProviderCatalog;
import com.tokengate.gateway.application.port.out.RateLimiter;
import com.tokengate.gateway.application.port.out.RequestLogEntry;
import com.tokengate.gateway.application.port.out.SemanticCacheStore;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.application.port.out.UsageRecorder;
import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.cache.CachedCompletion;
import com.tokengate.gateway.domain.cache.Vectors;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.CircuitBreaker;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;
import com.tokengate.gateway.domain.tenant.TenantId;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class Fakes {

    private Fakes() {
    }

    /** Plays back a queue of scripted behaviours, then keeps answering normally. */
    static final class ScriptedProvider implements ChatProvider {
        final ProviderId id;
        final Deque<Supplier<ChatCompletion>> unary = new ArrayDeque<>();
        final Deque<List<Object>> streams = new ArrayDeque<>();
        final List<ProviderCall> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger closedStreams = new AtomicInteger();
        volatile long delayMillis;

        ScriptedProvider(String id) {
            this.id = ProviderId.of(id);
        }

        ScriptedProvider failNext(FailureKind kind) {
            unary.add(() -> {
                throw new ProviderFailure(kind, id + " scripted " + kind);
            });
            return this;
        }

        /** items are content chunks, or a FailureKind to throw at that point; a leading FailureKind fails the open */
        ScriptedProvider streamNext(Object... items) {
            streams.add(List.of(items));
            return this;
        }

        @Override
        public ProviderId id() {
            return id;
        }

        @Override
        public ChatCompletion complete(ProviderCall call) {
            calls.add(call);
            sleep();
            Supplier<ChatCompletion> s = unary.poll();
            if (s != null) {
                return s.get();
            }
            return new ChatCompletion("[" + id + "] " + call.request().lastUserMessage().content(), "stop",
                    new TokenUsage(10, 5), call.nativeModel());
        }

        @Override
        public ChunkStream openStream(ProviderCall call) {
            calls.add(call);
            List<Object> script = streams.poll();
            if (script == null) {
                script = List.of("hello ", "from " + id);
            }
            if (!script.isEmpty() && script.get(0) instanceof FailureKind k) {
                throw new ProviderFailure(k, id + " refused stream");
            }
            Iterator<Object> it = script.iterator();
            AtomicBoolean closed = new AtomicBoolean();
            return new ChunkStream() {
                @Override
                public StreamDelta next() {
                    if (closed.get() || !it.hasNext()) {
                        return null;
                    }
                    Object o = it.next();
                    if (o instanceof FailureKind k) {
                        throw new ProviderFailure(k, id + " broke mid-stream");
                    }
                    return StreamDelta.text((String) o);
                }

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        closedStreams.incrementAndGet();
                    }
                }
            };
        }

        private void sleep() {
            if (delayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderFailure(FailureKind.TIMEOUT, "interrupted");
            }
        }
    }

    static final class Registry implements ChatProviderRegistry {
        final Map<ProviderId, ChatProvider> providers = new HashMap<>();

        Registry add(ChatProvider p) {
            providers.put(p.id(), p);
            return this;
        }

        @Override
        public Optional<ChatProvider> get(ProviderId id) {
            return Optional.ofNullable(providers.get(id));
        }
    }

    static final class Catalog implements ProviderCatalog {
        final Map<ProviderId, ProviderDescriptor> byId = new java.util.LinkedHashMap<>();

        Catalog add(ProviderDescriptor d) {
            byId.put(d.id(), d);
            return this;
        }

        @Override
        public List<ProviderDescriptor> all() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<ProviderDescriptor> find(ProviderId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public ProviderDescriptor updateSettings(ProviderId id, int weight, int priority, boolean enabled) {
            ProviderDescriptor d = byId.get(id).withSettings(weight, priority, enabled);
            byId.put(id, d);
            return d;
        }
    }

    static final class Limiter implements RateLimiter {
        volatile boolean deny;
        volatile boolean broken;

        @Override
        public RateLimitDecision tryAcquire(TenantId tenant, RateLimitRule rule, long permits) {
            if (broken) {
                throw new IllegalStateException("redis down");
            }
            return deny
                    ? new RateLimitDecision(false, rule.burst(), 0, Duration.ofSeconds(3), Duration.ofSeconds(6))
                    : new RateLimitDecision(true, rule.burst(), rule.burst() - 1, Duration.ZERO, Duration.ZERO);
        }
    }

    static final class Ledger implements BudgetLedger {
        final Map<String, Long> tokens = new ConcurrentHashMap<>();
        final List<BudgetReservation> released = new CopyOnWriteArrayList<>();
        final List<Long> settled = new CopyOnWriteArrayList<>();

        @Override
        public synchronized BudgetDecision reserve(BudgetReservation r, BudgetLimits limits) {
            long used = tokens.getOrDefault(r.tenantId(), 0L);
            if (used + r.tokens() > limits.dailyTokens()) {
                return new BudgetDecision(false, BudgetDecision.Reason.DAILY_TOKENS, used, limits.dailyTokens(), 0,
                        limits.monthlyCostCap().micros(), Duration.ofHours(1));
            }
            tokens.merge(r.tenantId(), r.tokens(), Long::sum);
            return new BudgetDecision(true, BudgetDecision.Reason.NONE, used + r.tokens(), limits.dailyTokens(), 0,
                    limits.monthlyCostCap().micros(), Duration.ZERO);
        }

        @Override
        public synchronized void settle(BudgetReservation r, long actualTokens, Cost actualCost) {
            tokens.merge(r.tenantId(), actualTokens - r.tokens(), Long::sum);
            settled.add(actualTokens);
        }

        @Override
        public synchronized void release(BudgetReservation r) {
            tokens.merge(r.tenantId(), -r.tokens(), Long::sum);
            released.add(r);
        }

        @Override
        public Usage usage(TenantId tenant, BudgetPeriod period) {
            return new Usage(tokens.getOrDefault(tenant.value(), 0L), Cost.ZERO);
        }
    }

    static final class Exact implements ExactCacheStore {
        final Map<String, CachedCompletion> map = new ConcurrentHashMap<>();

        @Override
        public Optional<CachedCompletion> get(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public void put(String key, CachedCompletion value, Duration ttl) {
            map.put(key, value);
        }
    }

    static final class Semantic implements SemanticCacheStore {
        record Row(String tenant, String context, String prompt, float[] vec, CachedCompletion value) {
        }

        final List<Row> rows = new CopyOnWriteArrayList<>();

        @Override
        public Optional<Hit> nearest(String tenantId, String contextKey, float[] embedding, double minSimilarity) {
            return rows.stream()
                    .filter(r -> r.tenant().equals(tenantId) && r.context().equals(contextKey))
                    .map(r -> new Hit(r.value(), Vectors.cosine(r.vec(), embedding), r.prompt()))
                    .filter(h -> h.similarity() >= minSimilarity)
                    .max(java.util.Comparator.comparingDouble(Hit::similarity));
        }

        @Override
        public void put(String tenantId, String contextKey, String maskedPrompt, float[] embedding,
                        CachedCompletion value, Duration ttl) {
            rows.add(new Row(tenantId, contextKey, maskedPrompt, embedding, value));
        }

        @Override
        public int purgeExpired() {
            return 0;
        }
    }

    /** Bag of words over a tiny fixed vocabulary, so paraphrases land close together and the test can reason about it. */
    static final class WordEmbeddings implements EmbeddingProvider {
        @Override
        public String modelName() {
            return "test-bow";
        }

        @Override
        public int dimensions() {
            return 64;
        }

        @Override
        public float[] embed(String text) {
            float[] v = new float[64];
            for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
                if (w.isEmpty() || w.equals("the") || w.equals("a") || w.equals("please")) {
                    continue;
                }
                v[Math.floorMod(w.hashCode(), 64)] += 1;
            }
            return Vectors.normalise(v);
        }
    }

    static final class WordTokens implements TokenCounter {
        @Override
        public String encoding() {
            return "words";
        }

        @Override
        public long countText(String text) {
            String t = text.strip();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }

        @Override
        public long countMessages(List<ChatMessage> messages) {
            return messages.stream().mapToLong(m -> countText(m.content()) + 3).sum() + 3;
        }
    }

    static final class Recorder implements UsageRecorder {
        final List<UsageEvent> events = new CopyOnWriteArrayList<>();
        final List<RequestLogEntry> entries = new CopyOnWriteArrayList<>();
        volatile boolean broken;

        @Override
        public void record(RequestLogEntry entry, UsageEvent event) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            entries.add(entry);
            events.add(event);
        }

        UsageEvent last() {
            return events.get(events.size() - 1);
        }
    }

    static final class Metrics implements GatewayMetrics {
        final List<String> failovers = new CopyOnWriteArrayList<>();
        final List<String> streamOutcomes = new CopyOnWriteArrayList<>();
        final AtomicInteger recordFailures = new AtomicInteger();

        @Override
        public void providerCall(ProviderId provider, Duration latency, String outcome) {
        }

        @Override
        public void cacheLookup(String tier, boolean hit) {
        }

        @Override
        public void tokens(String tenant, String provider, long prompt, long completion) {
        }

        @Override
        public void cost(String tenant, String provider, Cost cost) {
        }

        @Override
        public void rejected(String tenant, String reason) {
        }

        @Override
        public void failover(ProviderId from, ProviderId to) {
            failovers.add(from + "->" + to);
        }

        @Override
        public void piiMasked(String type, int count) {
        }

        @Override
        public void streamEnded(String outcome) {
            streamOutcomes.add(outcome);
        }

        @Override
        public void usageRecordFailed() {
            recordFailures.incrementAndGet();
        }

        @Override
        public void trackBreaker(ProviderId provider, Supplier<CircuitBreaker.State> state) {
        }
    }

    static final class RecordingSink implements StreamSink {
        final List<String> deltas = new ArrayList<>();
        String finish;
        TokenUsage usage;
        String errorType;
        int failAfterDeltas = -1;

        @Override
        public void delta(String content) throws java.io.IOException {
            if (failAfterDeltas >= 0 && deltas.size() >= failAfterDeltas) {
                throw new java.io.IOException("broken pipe");
            }
            deltas.add(content);
        }

        @Override
        public void finish(String finishReason, TokenUsage usage) {
            this.finish = finishReason;
            this.usage = usage;
        }

        @Override
        public void error(String type, String message) {
            this.errorType = type;
        }

        String text() {
            return String.join("", deltas);
        }
    }
}
