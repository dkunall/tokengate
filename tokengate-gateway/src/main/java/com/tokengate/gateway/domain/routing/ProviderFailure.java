package com.tokengate.gateway.domain.routing;

import java.time.Duration;
import java.util.Optional;

public class ProviderFailure extends RuntimeException {

    private final FailureKind kind;
    private final int upstreamStatus;
    private final Duration retryAfter;

    public ProviderFailure(FailureKind kind, int upstreamStatus, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
        this.retryAfter = retryAfter;
    }

    public ProviderFailure(FailureKind kind, String message) {
        this(kind, 0, message, null, null);
    }

    public static ProviderFailure http(int status, String body, Duration retryAfter) {
        String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
        return new ProviderFailure(FailureKind.fromHttpStatus(status), status, "upstream " + status + ": " + snippet,
                retryAfter, null);
    }

    public FailureKind kind() {
        return kind;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
