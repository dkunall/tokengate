package com.tokengate.gateway.adapter.out.provider;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The HTTP client's request timeout stops at the response headers, so a stream that goes quiet halfway would
 * otherwise hold a virtual thread forever. Each read arms a timer that closes the stream if nothing arrives.
 */
final class IdleWatchdog {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final Duration idle;
    private final Runnable onIdle;
    private final AtomicBoolean fired = new AtomicBoolean();
    private ScheduledFuture<?> pending;

    IdleWatchdog(Duration idle, Runnable onIdle) {
        this.idle = idle;
        this.onIdle = onIdle;
    }

    void arm() {
        pending = TIMER.schedule(() -> {
            fired.set(true);
            onIdle.run();
        }, idle.toMillis(), TimeUnit.MILLISECONDS);
    }

    void disarm() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    boolean fired() {
        return fired.get();
    }

    Duration idle() {
        return idle;
    }
}
