package com.tokengate.gateway.domain.routing;

/**
 * How a provider call failed, and what that allows. A chat completion is not idempotent from a billing point
 * of view: if the provider may have run the model, asking it again can charge twice. So a same-provider retry
 * only happens when the request provably did not run (connection refused, an explicit 429/503). Anything
 * ambiguous (timeout, 5xx) moves to the next provider instead, which is the trade-off: a possible double
 * charge across two providers in exchange for an answer.
 */
public enum FailureKind {
    CONNECT(true, true, true),
    RATE_LIMITED(true, true, false),
    UNAVAILABLE(true, true, true),
    TIMEOUT(false, true, true),
    SERVER_ERROR(false, true, true),
    AUTH(false, true, true),
    BAD_REQUEST(false, false, false),
    MIDSTREAM(false, false, true);

    private final boolean retrySameProvider;
    private final boolean failover;
    private final boolean countsAgainstBreaker;

    FailureKind(boolean retrySameProvider, boolean failover, boolean countsAgainstBreaker) {
        this.retrySameProvider = retrySameProvider;
        this.failover = failover;
        this.countsAgainstBreaker = countsAgainstBreaker;
    }

    public boolean retrySameProvider() {
        return retrySameProvider;
    }

    public boolean failover() {
        return failover;
    }

    public boolean countsAgainstBreaker() {
        return countsAgainstBreaker;
    }

    public static FailureKind fromHttpStatus(int status) {
        return switch (status) {
            case 429 -> RATE_LIMITED;
            case 503 -> UNAVAILABLE;
            case 401, 403 -> AUTH;
            case 504 -> TIMEOUT;
            default -> status >= 500 ? SERVER_ERROR : BAD_REQUEST;
        };
    }
}
