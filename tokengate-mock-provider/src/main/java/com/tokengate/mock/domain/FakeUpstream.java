package com.tokengate.mock.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/** One named fake provider: its current behaviour, call counters and the last N prompts it received. */
public final class FakeUpstream {

    public enum Verdict { OK, OUTAGE, INJECTED_ERROR }

    private static final int KEEP_REQUESTS = 50;

    private final String name;
    private final Behaviour initial;
    private volatile Behaviour behaviour;
    private final Deque<RecordedRequest> recent = new ArrayDeque<>();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public FakeUpstream(String name, Behaviour behaviour) {
        this.name = name;
        this.initial = behaviour;
        this.behaviour = behaviour;
    }

    public String name() {
        return name;
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    public void configure(Behaviour b) {
        this.behaviour = b;
    }

    public synchronized void reset() {
        behaviour = initial;
        recent.clear();
        calls.set(0);
        failures.set(0);
    }

    public Verdict admit(RecordedRequest request, RandomGenerator random) {
        calls.incrementAndGet();
        synchronized (this) {
            recent.addFirst(request);
            while (recent.size() > KEEP_REQUESTS) {
                recent.removeLast();
            }
        }
        Behaviour b = behaviour;
        if (b.outage()) {
            failures.incrementAndGet();
            return Verdict.OUTAGE;
        }
        if (b.errorRate() > 0 && random.nextDouble() < b.errorRate()) {
            failures.incrementAndGet();
            return Verdict.INJECTED_ERROR;
        }
        return Verdict.OK;
    }

    public long delayMillis(RandomGenerator random) {
        Behaviour b = behaviour;
        return b.latencyMs() + (b.jitterMs() == 0 ? 0 : random.nextLong(b.jitterMs() + 1));
    }

    public synchronized List<RecordedRequest> recent() {
        return new ArrayList<>(recent);
    }

    public long calls() {
        return calls.get();
    }

    public long failures() {
        return failures.get();
    }
}
