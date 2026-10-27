package com.tokengate.gateway.domain.routing;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * Count-based breaker. CLOSED keeps the last {@code windowSize} outcomes in a ring buffer and opens once at
 * least {@code minimumCalls} are recorded and the failure rate reaches the threshold. OPEN rejects until
 * {@code openFor} has passed, then HALF_OPEN lets {@code halfOpenTrials} calls through: all of them must
 * succeed to close, any failure re-opens.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, HALF_OPEN, OPEN }

    public record Config(int windowSize, int minimumCalls, double failureRateThreshold, Duration openFor,
                         int halfOpenTrials) {
        public Config {
            if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize || halfOpenTrials < 1) {
                throw new IllegalArgumentException("invalid breaker config");
            }
            if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
                throw new IllegalArgumentException("failureRateThreshold must be in (0, 1]");
            }
        }

        public static Config defaults() {
            return new Config(20, 5, 0.5, Duration.ofSeconds(15), 2);
        }
    }

    public interface Listener {
        void onTransition(State from, State to);
    }

    private final Config config;
    private final Clock clock;
    private final Listener listener;

    private final boolean[] window;
    private int next;
    private int recorded;
    private int failures;

    private State state = State.CLOSED;
    private long openedAtMillis;
    private int trialsInFlight;
    private int trialSuccesses;

    public CircuitBreaker(Config config, Clock clock, Listener listener) {
        this.config = config;
        this.clock = clock;
        this.listener = listener;
        this.window = new boolean[config.windowSize()];
    }

    public synchronized boolean tryAcquire() {
        if (state == State.OPEN && cooledDown()) {
            transition(State.HALF_OPEN);
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (trialsInFlight + trialSuccesses >= config.halfOpenTrials()) {
                    yield false;
                }
                trialsInFlight++;
                yield true;
            }
        };
    }

    /** Read-only version of {@link #tryAcquire()} for planning a route; takes no half-open permit. */
    public synchronized boolean wouldPermit() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> cooledDown();
            case HALF_OPEN -> trialsInFlight + trialSuccesses < config.halfOpenTrials();
        };
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            trialsInFlight = Math.max(0, trialsInFlight - 1);
            if (++trialSuccesses >= config.halfOpenTrials()) {
                transition(State.CLOSED);
            }
            return;
        }
        if (state == State.CLOSED) {
            record(false);
        }
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            transition(State.OPEN);
            return;
        }
        if (state == State.CLOSED) {
            record(true);
            if (recorded >= config.minimumCalls() && (double) failures / recorded >= config.failureRateThreshold()) {
                transition(State.OPEN);
            }
        }
    }

    /** The call ended in a way that says nothing about provider health (e.g. a 400 or a client cancel). */
    public synchronized void onIgnored() {
        if (state == State.HALF_OPEN) {
            trialsInFlight = Math.max(0, trialsInFlight - 1);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized double failureRate() {
        return recorded == 0 ? 0 : (double) failures / recorded;
    }

    private boolean cooledDown() {
        return clock.millis() - openedAtMillis >= config.openFor().toMillis();
    }

    private void record(boolean failed) {
        if (recorded == window.length) {
            if (window[next]) {
                failures--;
            }
        } else {
            recorded++;
        }
        window[next] = failed;
        if (failed) {
            failures++;
        }
        next = (next + 1) % window.length;
    }

    private void transition(State to) {
        State from = state;
        state = to;
        trialsInFlight = 0;
        trialSuccesses = 0;
        if (to == State.OPEN) {
            openedAtMillis = clock.millis();
        }
        if (to == State.CLOSED) {
            Arrays.fill(window, false);
            next = 0;
            recorded = 0;
            failures = 0;
        }
        if (from != to) {
            listener.onTransition(from, to);
        }
    }
}
